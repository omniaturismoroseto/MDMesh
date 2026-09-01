/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * <p>The device's <b>desired state</b>, delivered on every check-in.</p>
 *
 * <p>This is the piece that was missing. The check-in response used to carry
 * commands only, so anything that was not an explicit command — that is, the
 * whole configuration saved in the console — never left the server. A setting
 * could be changed and saved and simply never arrive, with nothing to show for
 * it: the console displayed the saved value while the device held another.</p>
 *
 * <p>A command is an order given once: if the device is switched off at that
 * moment, the order is lost. This is instead a <i>description of how the device
 * should be</i>, repeated at every check-in — so a device that was off for three
 * days aligns itself as soon as it comes back, and one whose setting was changed
 * by hand is put right on the next round. Across a fleet that is the difference
 * between a system that holds and one that must be checked device by device.</p>
 *
 * <p>Every field is optional on purpose: an older agent ignores this block and
 * keeps working, an older server never sends it and a newer agent simply has
 * nothing to reconcile. Neither side breaks when the other is behind — see
 * {@code proto/VERSIONING.md}.</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDesiredState {

    /**
     * Wanted kiosk, in the same shape as the {@code kiosk.enter} payload. Kept as
     * a raw node rather than a typed class: the agent owns that schema and adds
     * fields to it over time, and a server-side mirror would have to be edited in
     * lockstep — the kind of coupling that quietly drifts. Absent (null) means
     * <b>no kiosk</b>: a device currently in one must leave it.
     */
    private JsonNode kiosk;

    /**
     * Wanted toggles, keyed exactly as {@code policy.apply} already keys them
     * ({@code wifi}, {@code bluetooth}, {@code camera}, …). An <b>absent</b> key
     * means unmanaged, which is not the same as {@code false}: "leave it alone"
     * versus "turn it off".
     */
    private Map<String, Boolean> policies;

    /**
     * Bumped whenever the configuration is saved. When it matches what the device
     * already applied there is nothing to do. Not an optimisation: without it the
     * kiosk would be re-applied on every check-in, and that is visible on screen.
     */
    private long revision;

    public AgentDesiredState() {
    }
}
