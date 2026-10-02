package com.linen.laundry.mapper;

import java.util.Map;

/**
 * 批次隧道视图动态 SQL：按状态 / 停留工序 / 品类过滤，并按「在产优先」排序。
 */
public class WashBatchSqlProvider {

    public String tunnel(Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.*, c.name AS category_name, m.name AS machine_name, m.code AS machine_code, ")
                .append(" qs.source AS quarantine_source, qs.active_count AS active_quarantine_count, ")
                .append(" ROUND(b.current_step_order * 100.0 / 8, 0) AS progress ")
                .append(" FROM wash_batch b ")
                .append(" LEFT JOIN linen_category c ON c.id = b.category_id ")
                .append(" LEFT JOIN machine m ON m.id = b.machine_id ")
                .append(" LEFT JOIN (")
                .append("   SELECT q.batch_id, GROUP_CONCAT(CONCAT(e.event_no, ' ', e.pollution_type, ' @', ma.code) SEPARATOR '；') AS source, COUNT(*) AS active_count")
                .append("     FROM quarantine_record q")
                .append("     JOIN contamination_event e ON e.id = q.event_id")
                .append("     JOIN machine ma ON ma.id = e.machine_id")
                .append("    WHERE q.status = 'ACTIVE'")
                .append("    GROUP BY q.batch_id")
                .append(" ) qs ON qs.batch_id = b.id")
                .append(" WHERE b.status <> 'DELETED' ");

        Object status = params.get("status");
        if (status != null && !status.toString().isBlank()) {
            sql.append(" AND b.status = #{status} ");
        }
        Object stepCode = params.get("stepCode");
        if (stepCode != null && !stepCode.toString().isBlank()) {
            sql.append(" AND b.current_step = #{stepCode} ");
        }
        Object categoryId = params.get("categoryId");
        if (categoryId != null && !categoryId.toString().isBlank()) {
            sql.append(" AND b.category_id = #{categoryId} ");
        }
        sql.append(" ORDER BY FIELD(b.status, 'QUARANTINED', 'RUNNING', 'HOLD', 'DISPOSED', 'DONE'), b.current_step_order, b.start_time DESC ");
        sql.append(" LIMIT 100 ");
        return sql.toString();
    }
}
