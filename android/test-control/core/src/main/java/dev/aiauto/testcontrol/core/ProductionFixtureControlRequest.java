package dev.aiauto.testcontrol.core;

/**
 * 功能用途：严格解析 N47 loopback 控制端点的固定单行请求，拒绝重复键和协议扩展。
 */

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ProductionFixtureControlRequest(
    String command,
    String runId,
    String token
) {
    private static final Pattern REQUEST = Pattern.compile(
        "^\\{\"command\":\"(setup|stop)\","
            + "\"runId\":\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
            + "[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-"
            + "[0-9a-fA-F]{12})\","
            + "\"token\":\"([A-Za-z0-9_]{1,128})\"\\}$"
    );

    public ProductionFixtureControlRequest {
        command = requireText(command, "command");
        runId = requireText(runId, "runId");
        token = requireText(token, "token");
    }

    public static ProductionFixtureControlRequest parse(String line) {
        if (line == null || line.length() > 512 || line.indexOf('\n') >= 0
            || line.indexOf('\r') >= 0) {
            throw protocolFailure();
        }
        Matcher matcher = REQUEST.matcher(line);
        if (!matcher.matches()) {
            throw protocolFailure();
        }
        return new ProductionFixtureControlRequest(
            matcher.group(1),
            matcher.group(2).toLowerCase(java.util.Locale.ROOT),
            matcher.group(3)
        );
    }

    @Override
    public String toString() {
        return "ProductionFixtureControlRequest[command=" + command
            + ", runId=" + runId + ", token=<redacted>]";
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static TestControlException protocolFailure() {
        return new TestControlException(
            TestControlError.CONTROL_PROTOCOL_INVALID,
            "Production fixture control request rejected: CONTROL_PROTOCOL_INVALID"
        );
    }
}
