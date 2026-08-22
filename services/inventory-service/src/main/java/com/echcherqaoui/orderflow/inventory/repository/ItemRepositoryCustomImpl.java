package com.echcherqaoui.orderflow.inventory.repository;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ItemRepositoryCustomImpl implements ItemRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    public void incrementStockBatchByCounts(@NonNull Map<UUID, Integer> itemCounts) {
        if (itemCounts.isEmpty())
            return;

        String sql = "UPDATE items SET remaining_units = remaining_units + ? WHERE id = ?";

        List<Map.Entry<UUID, Integer>> entries = itemCounts.entrySet().stream().toList();

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                ps.setInt(1, entries.get(i).getValue());
                ps.setObject(2, entries.get(i).getKey());
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });
    }
}