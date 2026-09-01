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

package com.hmdm.rest.resource.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.agent.AgentDesiredState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Turns a device's configuration into the {@link AgentDesiredState} carried by every
 * check-in — the counterpart of {@link ConfigAppInstaller}, which does the same for the app
 * list but as one-shot commands.</p>
 *
 * <p>The difference matters. Commands are orders given once: a device switched off when the
 * order is issued never receives it, and nothing says so. The desired state is instead
 * repeated on every check-in, so a device that was away realigns by itself and one whose
 * settings were changed by hand is put right on the next round.</p>
 *
 * <p>Nothing here writes to the database and nothing throws: a dirty configuration must
 * degrade to "no desired state" — leaving the device as it is — rather than break the
 * check-in, which is the device's only way home.</p>
 */
@Singleton
public class DesiredStateBuilder {

    private static final Logger logger = LoggerFactory.getLogger(DesiredStateBuilder.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UnsecureDAO unsecureDAO;

    @Inject
    public DesiredStateBuilder(UnsecureDAO unsecureDAO) {
        this.unsecureDAO = unsecureDAO;
    }

    /**
     * Builds the desired state for a device, or {@code null} when there is nothing to say
     * (no configuration, or it could not be read). Never throws.
     */
    public AgentDesiredState build(Device device) {
        if (device == null || device.getConfigurationId() == null) {
            return null;
        }
        try {
            Configuration cfg = unsecureDAO.getConfigurationById(device.getConfigurationId());
            if (cfg == null) {
                return null;
            }

            AgentDesiredState desired = new AgentDesiredState();
            desired.setKiosk(kiosk(cfg));
            desired.setPolicies(policies(cfg));
            // The revision is a fingerprint of the content, not a save counter: saving a
            // configuration without changing anything the device cares about leaves it
            // unchanged, so nothing is re-applied and nothing flickers on screen. It also
            // spares a database migration for a column that would say less.
            desired.setRevision(fingerprint(desired));
            return desired;
        } catch (Exception e) {
            logger.warn("Could not build desired state for device {}", device.getNumber(), e);
            return null;
        }
    }

    /**
     * The wanted kiosk, in the shape of the {@code kiosk.enter} payload, or {@code null} when
     * kiosk mode is off — which the agent reads as "leave the kiosk if you are in one".
     */
    private ObjectNode kiosk(Configuration cfg) {
        if (!cfg.isKioskMode()) {
            return null;
        }
        ObjectNode kiosk = MAPPER.createObjectNode();

        String pinPackage = mainAppPackage(cfg);
        // "single" pins one app and makes it the home screen; "launcher" shows the grid of
        // allowed apps. Without a main app there is nothing to pin, so the grid is the only
        // sensible reading of "kiosk on".
        kiosk.put("mode", pinPackage != null ? "single" : "launcher");
        if (pinPackage != null) {
            kiosk.put("pinPackage", pinPackage);
        }

        ArrayNode allowed = kiosk.putArray("allowedPackages");
        for (String pkg : allowedPackages(cfg, pinPackage)) {
            allowed.add(pkg);
        }

        // Tri-state on purpose: null means "not managed", which the agent leaves alone. It is
        // not the same as false, and collapsing the two would silently start switching off
        // things nobody asked about.
        ObjectNode features = kiosk.putObject("features");
        putNullable(features, "home", cfg.getKioskHome());
        putNullable(features, "recents", cfg.getKioskRecents());
        putNullable(features, "notifications", cfg.getKioskNotifications());
        putNullable(features, "systemInfo", cfg.getKioskSystemInfo());
        putNullable(features, "keyguard", cfg.getKioskKeyguard());
        putNullable(features, "lockButtons", cfg.getKioskLockButtons());

        // A visible exit button, when asked for; otherwise the discreet gesture. "remote" —
        // console only — is deliberately not derived from the configuration: locking every
        // device out of local escape by ticking a box is a decision that deserves its own,
        // more explicit gesture than this one.
        kiosk.put("exitMode", Boolean.TRUE.equals(cfg.getKioskExit()) ? "visible" : "gesture");

        return kiosk;
    }

    /**
     * Packages the kiosk may open besides the pinned app: the "allowed app classes" list of the
     * configuration. On a service device this is what keeps the dialer and the camera reachable
     * — without them a locked phone cannot call 112 or take a photo.
     */
    private List<String> allowedPackages(Configuration cfg, String pinPackage) {
        List<String> packages = new ArrayList<>();
        if (pinPackage != null) {
            packages.add(pinPackage);
        }
        String csv = cfg.getAllowedClasses();
        if (csv != null) {
            for (String raw : csv.split(",")) {
                String pkg = raw.trim();
                if (!pkg.isEmpty() && !packages.contains(pkg)) {
                    packages.add(pkg);
                }
            }
        }
        return packages;
    }

    private String mainAppPackage(Configuration cfg) {
        if (cfg.getMainAppId() == null) {
            return null;
        }
        Application app = unsecureDAO.findApplicationById(cfg.getMainAppId());
        if (app == null || app.getPkg() == null || app.getPkg().trim().isEmpty()) {
            return null;
        }
        return app.getPkg().trim();
    }

    /**
     * Wanted toggles, keyed as {@code policy.apply} keys them. Only the ones actually managed
     * are present: the console's tri-states (Auto / On / Off) map Auto to an absent key, and
     * "unmanaged" must stay distinguishable from "off".
     */
    private Map<String, Boolean> policies(Configuration cfg) {
        Map<String, Boolean> policies = new LinkedHashMap<>();
        putIfManaged(policies, "wifi", cfg.getWifi());
        putIfManaged(policies, "bluetooth", cfg.getBluetooth());
        putIfManaged(policies, "gps", cfg.getGps());
        putIfManaged(policies, "usbStorage", cfg.getUsbStorage());
        return policies;
    }

    private static void putIfManaged(Map<String, Boolean> target, String key, Boolean value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putNullable(ObjectNode target, String field, Boolean value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value);
        }
    }

    /**
     * A stable fingerprint of the desired content. Stable is the whole point: the same
     * configuration must produce the same number on every check-in, or the agent would
     * re-apply the kiosk continuously. Jackson keeps insertion order for object nodes and the
     * policy map is a LinkedHashMap, so the serialisation is deterministic.
     */
    private static long fingerprint(AgentDesiredState desired) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("kiosk", desired.getKiosk());
            content.put("policies", desired.getPolicies());
            byte[] json = MAPPER.writeValueAsBytes(content);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            long value = 0L;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xFFL);
            }
            return value >>> 1; // keep it positive: the wire type is a plain integer
        } catch (Exception e) {
            // A revision that changes every time is bad (constant re-apply) but a check-in that
            // fails is worse. Fall back to something stable-ish and let the agent's own
            // comparison of the content decide.
            logger.warn("Could not fingerprint the desired state", e);
            return 0L;
        }
    }

}
