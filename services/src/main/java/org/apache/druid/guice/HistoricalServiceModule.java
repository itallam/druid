/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.guice;

import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;
import org.apache.druid.discovery.DataNodeService;
import org.apache.druid.discovery.DruidService;
import org.apache.druid.discovery.LookupNodeService;
import org.apache.druid.discovery.NodeRole;

public class HistoricalServiceModule extends AbstractDruidServiceModule
{
  @ProvidesIntoSet
  @Named(NodeRole.HISTORICAL_JSON_NAME)
  public Class<? extends DruidService> getDataNodeService()
  {
    return DataNodeService.class;
  }

  @ProvidesIntoSet
  @Named(NodeRole.HISTORICAL_JSON_NAME)
  public Class<? extends DruidService> getLookupNodeService()
  {
    return LookupNodeService.class;
  }

  @Override
  protected NodeRole getNodeRoleKey()
  {
    return NodeRole.HISTORICAL;
  }
}
