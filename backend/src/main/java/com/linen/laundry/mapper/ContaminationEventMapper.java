package com.linen.laundry.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.linen.laundry.domain.ContaminationEvent;

/**
 * 污染事件主表。确认和放行均用条件 UPDATE 配合行锁保证并发下只落一份结果。
 */
@Mapper
public interface ContaminationEventMapper {

    @Select("""
            SELECT e.*, m.code AS machine_code, m.name AS machine_name,
                   (SELECT COUNT(*) FROM quarantine_record q
                     WHERE q.event_id = e.id AND q.status = 'ACTIVE') AS quarantine_count,
                   (SELECT COUNT(*) FROM quarantine_record q
                     WHERE q.event_id = e.id AND q.status = 'ACTIVE'
                       AND NOT EXISTS (
                           SELECT 1 FROM quarantine_record_rewash qr
                             JOIN rewash_link r ON r.id = qr.rewash_id
                            WHERE qr.quarantine_id = q.id AND r.status = 'DONE'
                       )) AS pending_count,
                   (SELECT COUNT(*) FROM quarantine_record_rewash qr
                     JOIN rewash_link r ON r.id = qr.rewash_id
                     JOIN quarantine_record qq ON qq.id = qr.quarantine_id
                    WHERE qq.event_id = e.id) AS rewash_count
              FROM contamination_event e
              JOIN machine m ON m.id = e.machine_id
             ORDER BY FIELD(e.status, 'CONFIRMED', 'PENDING', 'RELEASED'), e.created_at DESC, e.id DESC
            """)
    @Results(id = "eventMap", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "eventNo", column = "event_no"),
            @Result(property = "machineId", column = "machine_id"),
            @Result(property = "reporter", column = "reporter"),
            @Result(property = "foundTime", column = "found_time"),
            @Result(property = "impactStartTime", column = "impact_start_time"),
            @Result(property = "impactEndTime", column = "impact_end_time"),
            @Result(property = "pollutionType", column = "pollution_type"),
            @Result(property = "description", column = "description"),
            @Result(property = "status", column = "status"),
            @Result(property = "confirmedBy", column = "confirmed_by"),
            @Result(property = "confirmedAt", column = "confirmed_at"),
            @Result(property = "releasedBy", column = "released_by"),
            @Result(property = "releasedAt", column = "released_at"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "machineCode", column = "machine_code"),
            @Result(property = "machineName", column = "machine_name"),
            @Result(property = "quarantineCount", column = "quarantine_count"),
            @Result(property = "pendingCount", column = "pending_count"),
            @Result(property = "rewashCount", column = "rewash_count"),
            @Result(property = "quarantineRecords", column = "id",
                    many = @Many(
                            select = "com.linen.laundry.mapper.QuarantineRecordMapper.selectByEventId"))
    })
    List<ContaminationEvent> selectAll();

    @Select("""
            SELECT e.*, m.code AS machine_code, m.name AS machine_name
              FROM contamination_event e
              JOIN machine m ON m.id = e.machine_id
             WHERE e.id = #{id}
            """)
    @ResultMap("eventMap")
    ContaminationEvent selectById(@Param("id") Long id);

    @Select("""
            SELECT e.*, m.code AS machine_code, m.name AS machine_name
              FROM contamination_event e
              JOIN machine m ON m.id = e.machine_id
             WHERE e.id = #{id}
             FOR UPDATE
            """)
    @ResultMap("eventMap")
    ContaminationEvent selectByIdForUpdate(@Param("id") Long id);

    @Insert("""
            INSERT INTO contamination_event
                (event_no, machine_id, reporter, found_time, impact_start_time, impact_end_time,
                 pollution_type, description, status, created_at)
            VALUES
                (#{eventNo}, #{machineId}, #{reporter}, #{foundTime}, #{impactStartTime}, #{impactEndTime},
                 #{pollutionType}, #{description}, #{status}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ContaminationEvent event);

    @Update("""
            UPDATE contamination_event
               SET status = 'CONFIRMED', confirmed_by = #{operator}, confirmed_at = NOW()
             WHERE id = #{id} AND status = 'PENDING'
            """)
    int confirm(@Param("id") Long id, @Param("operator") String operator);

    @Update("""
            UPDATE contamination_event
               SET status = 'RELEASED', released_by = #{operator}, released_at = NOW()
             WHERE id = #{id} AND status = 'CONFIRMED'
            """)
    int release(@Param("id") Long id, @Param("operator") String operator);

    @Select("""
            SELECT COUNT(*) FROM contamination_event
             WHERE machine_id = #{machineId} AND status = 'CONFIRMED'
            """)
    int countActiveByMachine(@Param("machineId") Long machineId);
}
