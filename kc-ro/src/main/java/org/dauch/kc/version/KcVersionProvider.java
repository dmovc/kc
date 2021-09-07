/*
 * Copyright 2021 Dzmiter Auchynnikau
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dauch.kc.version;

import groovyjarjarpicocli.CommandLine;

import java.util.Properties;

public class KcVersionProvider implements CommandLine.IVersionProvider {
  @Override
  public String[] getVersion() throws Exception {
    var cl = Thread.currentThread().getContextClassLoader();
    var props = new Properties();
    try (var is = cl.getResourceAsStream("main.properties")) {
      props.load(is);
    }
    return new String[]{
      "Version " + props.getProperty("version"),
      "(c) " + props.getProperty("year") + " Dzmiter Auchynnikau"
    };
  }
}