package org.ku.kc.commands;

import groovy.json.JsonOutput;
import groovyjarjarpicocli.CommandLine.Command;
import groovyjarjarpicocli.CommandLine.Option;
import groovyjarjarpicocli.CommandLine.Parameters;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.common.TopicPartition;
import org.apache.karaf.shell.table.ShellTable;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static groovyjarjarpicocli.CommandLine.Help.Visibility.ALWAYS;
import static java.util.Collections.emptyList;

@Command(
  name = "groups",
  aliases = {"g"},
  description = "Consumer groups",
  mixinStandardHelpOptions = true
)
public class ConsumerGroupsCommand extends AbstractAdminClientCommand implements Callable<Integer> {

  @Option(
    names = {"--iao", "--include-authorized-operations"},
    description = "Whether include authorized operations or not",
    defaultValue = "false",
    showDefaultValue = ALWAYS
  )
  private boolean includeAuthorizedOperations;

  @Parameters(
    description = "Groups"
  )
  public List<String> groups = emptyList();

  @Override
  public Integer call() throws Exception {
    try (var client = AdminClient.create(clientProps())) {
      var opts = new DescribeConsumerGroupsOptions();
      opts.timeoutMs((int) timeout.toMillis());
      opts.includeAuthorizedOperations(includeAuthorizedOperations);
      var r = client.describeConsumerGroups(groups, opts).all().get();
      if (!quiet) {
        var table = new ShellTable();
        table.column("Group").alignLeft();
        table.column("State").alignCenter();
        table.column("Coordinator").alignLeft();
        table.column("Members").alignRight();
        table.column("PAssignor").alignCenter();
        r.forEach((group, description) -> table.addRow().addContent(
          group,
          description.state().toString(),
          description.coordinator(),
          description.members().size(),
          description.partitionAssignor()
        ));
        table.print(err);
      }
      var map = new TreeMap<String, LinkedHashMap<String, Object>>();
      r.forEach((group, description) -> {
        var ops = Stream.ofNullable(description.authorizedOperations())
          .flatMap(Set::stream)
          .sorted(Comparator.comparingInt(e -> Byte.toUnsignedInt(e.code())))
          .map(o -> Map.of("code", Byte.toUnsignedInt(o.code()), "op", o.name()))
          .collect(Collectors.toList());
        var members = description.members().stream()
          .map(m -> {
            var assignment = m.assignment().topicPartitions().stream()
              .collect(Collectors.toMap(
                TopicPartition::topic,
                TopicPartition::partition,
                (p1, p2) -> p2,
                TreeMap::new
              ));
            var memberMap = new TreeMap<String, Object>();
            memberMap.put("host", m.host());
            memberMap.put("consumerId", m.consumerId());
            memberMap.put("groupInstanceId", m.groupInstanceId());
            memberMap.put("clientId", m.clientId());
            memberMap.put("assignment", assignment);
            return memberMap;
          })
          .collect(Collectors.toList());
        var coordinator = new LinkedHashMap<String, Object>();
        coordinator.put("id", description.coordinator().idString());
        coordinator.put("host", description.coordinator().host());
        coordinator.put("port", description.coordinator().port());
        coordinator.put("rack", description.coordinator().rack());
        coordinator.put("hostRack", description.coordinator().hasRack());
        var gm = new LinkedHashMap<String, Object>();
        gm.put("partitionAssignor", description.partitionAssignor());
        gm.put("state", description.state().toString());
        gm.put("groupId", description.groupId());
        gm.put("simple", description.isSimpleConsumerGroup());
        gm.put("coordinator", coordinator);
        gm.put("ops", ops);
        gm.put("members", members);
        map.put(group, gm);
      });
      out.println(finalOutput(JsonOutput.toJson(map)));
    }
    return 0;
  }
}
