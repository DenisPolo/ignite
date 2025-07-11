/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.management;

import java.util.function.Consumer;
import org.apache.ignite.Ignite;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.internal.management.api.NativeCommand;
import org.apache.ignite.internal.management.api.NoArg;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.cluster.ClusterState.ACTIVE;

/** */
@Deprecated
public class ActivateCommand implements NativeCommand<NoArg, NoArg> {
    /** {@inheritDoc} */
    @Override public String description() {
        return "Activate cluster (deprecated. Use --set-state instead)";
    }

    /** {@inheritDoc} */
    @Override public String deprecationMessage(NoArg arg) {
        return "Command deprecated. Use --set-state instead.";
    }

    /** {@inheritDoc} */
    @Override public NoArg execute(
        @Nullable IgniteClient client,
        @Nullable Ignite ignite,
        NoArg arg,
        Consumer<String> printer
    ) {
        if (client != null)
            client.cluster().state(ACTIVE);
        else
            ignite.cluster().state(ACTIVE);

        printer.accept("Cluster activated");

        return null;
    }

    /** {@inheritDoc} */
    @Override public Class<NoArg> argClass() {
        return NoArg.class;
    }
}
