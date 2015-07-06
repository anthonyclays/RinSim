/*
 * Copyright (C) 2011-2015 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.central.rt;

import com.github.rinde.rinsim.central.GlobalStateObject;

/**
 * @author Rinde van Lon
 *
 */
public interface RealtimeSolver {

  void init(Scheduler scheduler);

  // will be called from a dedicated thread, this thread should be freed as soon
  // as possible to avoid delaying receiving of a second call
  void receiveSnapshot(GlobalStateObject snapshot);

}
