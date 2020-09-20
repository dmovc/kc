package org.ku.kc.commands;

import groovyjarjarpicocli.CommandLine.Option;

import java.net.URI;

import static groovyjarjarpicocli.CommandLine.Help.Visibility.ALWAYS;

public abstract class AbstractKafkaDataCommand extends AbstractKafkaCommand {

  @Option(
    names = {"--schema-registry"},
    paramLabel = "<schema-registry-url>",
    description = "Schema registry URL",
    defaultValue = "${env:SCHEMA_REGISTRY:-http://localhost:2181/}",
    showDefaultValue = ALWAYS
  )
  public URI schemaRegistry;

}