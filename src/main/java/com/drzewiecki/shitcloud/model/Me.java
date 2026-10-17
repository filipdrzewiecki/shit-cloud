package com.drzewiecki.shitcloud.model;

import java.util.List;

public record Me(String id, String displayName, List<String> groups, boolean builder, boolean admin, boolean guest) {
}
