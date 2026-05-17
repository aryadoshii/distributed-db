package db.sql.executor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Row {

    private final LinkedHashMap<String, Object> data;

    public Row() {
        this.data = new LinkedHashMap<>();
    }

    public Row(LinkedHashMap<String, Object> data) {
        this.data = new LinkedHashMap<>(data);
    }

    public void put(String column, Object value) {
        data.put(column, value);
    }

    public Object get(String column) {
        return data.get(column);
    }

    public boolean hasColumn(String column) {
        return data.containsKey(column);
    }

    public Set<String> columns() {
        return data.keySet();
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(data);
    }

    public Row project(List<String> columns) {
        Row projected = new Row();
        for (String col : columns) {
            projected.put(col, data.get(col));
        }
        return projected;
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
