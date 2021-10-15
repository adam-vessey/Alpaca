/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.islandora.alpaca.support.config;

/**
 * This condition enables a bean/configuration when the specified property is true
 *
 * Implementations must provide a no-arg constructor.
 *
 * @author pwinckles
 */
public abstract class ConditionOnPropertyTrue extends ConditionOnProperty<Boolean> {

  /**
   * Basic constructor
   * @param name the name of the property to check.
   * @param defaultValue the value to pass in and test.
   */
  public ConditionOnPropertyTrue(final String name, final boolean defaultValue) {
    super(name, true, defaultValue, Boolean.class);
  }

}
