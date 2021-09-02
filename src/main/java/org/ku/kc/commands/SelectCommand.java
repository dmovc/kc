package org.ku.kc.commands;

import groovyjarjarpicocli.CommandLine.Command;
import groovyjarjarpicocli.CommandLine.Option;
import groovyjarjarpicocli.CommandLine.Parameters;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

@Command(
  name = "select",
  aliases = {"s"},
  description = "Select command",
  mixinStandardHelpOptions = true,
  showDefaultValues = true
)
public class SelectCommand extends AbstractFetchCommand implements Callable<Integer> {

  @Parameters(
    description = "Input topic:partition:offset pairs"
  )
  public List<String> tpos;

  @Option(
    names = {"-c"},
    description = "Message count",
    defaultValue = "1"
  )
  public long count;

  private IllegalArgumentException formatException(String[] a, Throwable cause) {
    throw new IllegalArgumentException("Invalid topic:partition:offset format: " + String.join(":", a), cause);
  }

  private int partition(String[] a) {
    try {
      return Integer.parseInt(a[1]);
    } catch (Exception e) {
      throw formatException(a, e);
    }
  }

  private long offset(String[] a) {
    try {
      return Long.parseLong(a[2]);
    } catch (Exception e) {
      throw formatException(a, e);
    }
  }

  private List<Map<TopicPartition, Long>> offsets(KafkaConsumer<?, ?> consumer, TopicPartition tp) {
    var bof = (Function<Collection<TopicPartition>, Map<TopicPartition, Long>>) consumer::beginningOffsets;
    var eof = (Function<Collection<TopicPartition>, Map<TopicPartition, Long>>) consumer::endOffsets;
    return Stream.of(bof, eof).parallel().map(e -> e.apply(singletonList(tp))).collect(toList());
  }

  private ConcurrentMap<String, List<Entry>> parseTpos(KafkaConsumer<?, ?> consumer) {
    return tpos.parallelStream()
      .map(s -> s.split(":"))
      .collect(Collectors.groupingByConcurrent(
        a -> a[0],
        ConcurrentSkipListMap::new,
        Collectors.flatMapping(
          a -> {
            switch (a.length) {
              case 2: {
                var tp = new TopicPartition(a[0], partition(a));
                var offsets = offsets(consumer, tp);
                var bo = offsets.get(0).get(tp);
                var eo = offsets.get(1).get(tp);
                if (bo != null && eo != null) {
                  if (bo.longValue() == eo.longValue()) {
                    return Stream.empty();
                  } else {
                    return Stream.of(new Entry(tp.partition(), bo, eo));
                  }
                } else {
                  return Stream.empty();
                }
              }
              case 3: {
                var tp = new TopicPartition(a[0], partition(a));
                long offset = offset(a);
                var offsets = offsets(consumer, tp);
                var bo = offsets.get(0).get(tp);
                var eo = offsets.get(1).get(tp);
                if (bo != null && eo != null) {
                  if (offset >= bo && offset <= eo) {
                    if (bo.longValue() == eo.longValue()) {
                      return Stream.empty();
                    } else {
                      return Stream.of(new Entry(tp.partition(), offset, eo));
                    }
                  }
                } else {
                  return Stream.empty();
                }
              }
              default: throw formatException(a, null);
            }
          },
          toList()
        )
      ));
  }

  @Override
  public Integer call() {
    var state = new FetchState();
    try (var consumer = new KafkaConsumer<>(consumerProps(), BAD, BAD)) {
      var counter = new AtomicInteger();
      var tpos = parseTpos(consumer);
      var allTopics = consumer.listTopics();
      tpos.forEach((t, pos) -> {
        var infos = allTopics.get(t);
        if (infos == null) {
          throw new IllegalArgumentException("No such topic: " + t);
        }
        pos.parallelStream().forEach(po -> {
          if (infos.stream().noneMatch(i -> i.partition() == po.partition)) {
            throw new IllegalArgumentException("No partition found for " + t + ": " + po.partition);
          }
        });
      });
      var tps = tpos.entrySet().parallelStream()
        .flatMap(e -> e.getValue().stream().map(v -> new TopicPartition(e.getKey(), v.partition)))
        .collect(Collectors.toSet());
      consumer.assign(tps);
      while (!tpos.isEmpty()) {
        var pollResult = consumer.poll(pollTimeout);
        pollResult.partitions().parallelStream().forEach(tp -> {
          var rawRecords = pollResult.records(tp);
          var lastRawRecord = rawRecords.get(rawRecords.size() - 1);
          tpos.compute(tp.topic(), (t, old) -> {
            if (old == null) {
              return null;
            } else {
              old.removeIf(e -> lastRawRecord.offset() >= e.offset || lastRawRecord.offset() >= e.endOffset);
              return old.isEmpty() ? null : old;
            }
          });
          rawRecords.parallelStream()
            .filter(r -> {
              var pos = tpos.get(r.topic());
              if (pos == null) {
                return false;
              } else {
                return pos.stream().anyMatch(e -> r.offset() >= e.offset && r.offset() <= e.endOffset);
              }
            })
            .map(r -> {
              var a = new Object[]{
                r,
                keyFormat.decode(r.key(), state.keyDecoderProps),
                valueFormat.decode(r.value(), state.valueDecoderProps)
              };
              return Map.entry(a, r);
            })
            .filter(e -> state.filter.call(e.getKey()))
            .filter(e -> counter.getAndIncrement() <= count)
            .map(e -> state.projection.call(e.getKey()))
            .map(state.outputFormatter::format)
            .forEachOrdered(out::println);
        });
      }
      if (!quiet) {
        err.printf("Count: %d%n", counter.get());
      }
    }
    return 0;
  }

  private static final class Entry {

    private final int partition;
    private final long offset;
    private final long endOffset;

    private Entry(int partition, long offset, long endOffset) {
      this.partition = partition;
      this.offset = offset;
      this.endOffset = endOffset;
    }

    @Override
    public String toString() {
      return String.format("%d:%d:%d", partition, offset, endOffset);
    }
  }
}
