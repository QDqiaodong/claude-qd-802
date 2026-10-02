package com.linen.laundry.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.One;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.linen.laundry.domain.QuarantineRecord;

/**
 * 污染隔离记录及复洗关联。
 */
@Mapper
public interface QuarantineRecordMapper {

    @Select("""
            SELECT q.*, e.event_no, e.status AS event_status, e.pollution_type,
                   e.machine_id, e.impact_start_time, e.impact_end_time,
                   m.code AS machine_code, m.name AS machine_name
              FROM quarantine_record q
              JOIN contamination_event e ON e.id = q.event_id
              JOIN machine m ON m.id = e.machine_id
             WHERE q.event_id = #{eventId}
             ORDER BY q.id
            """)
    @Results(id = "quarantineMap", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "eventId", column = "event_id"),
            @Result(property = "batchId", column = "batch_id"),
            @Result(property = "status", column = "status"),
            @Result(property = "hitReason", column = "hit_reason"),
            @Result(property = "createdBy", column = "created_by"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "releasedAt", column = "released_at"),
            @Result(property = "eventNo", column = "event_no"),
            @Result(property = "eventStatus", column = "event_status"),
            @Result(property = "pollutionType", column = "pollution_type"),
            @Result(property = "machineId", column = "machine_id"),
            @Result(property = "impactStartTime", column = "impact_start_time"),
            @Result(property = "impactEndTime", column = "impact_end_time"),
            @Result(property = "machineCode", column = "machine_code"),
            @Result(property = "machineName", column = "machine_name"),
            @Result(property = "batch", column = "batch_id",
                    one = @One(
                            select = "com.linen.laundry.mapper.WashBatchMapper.selectById")),
            @Result(property = "hitSteps", column = "id",
                    many = @Many(
                            select = "com.linen.laundry.mapper.QuarantineHitStepMapper.selectByQuarantineId")),
            @Result(property = "rewashLinks", column = "id",
                    many = @Many(
                            select = "com.linen.laundry.mapper.RewashLinkMapper.selectByQuarantineId"))
    })
    List<QuarantineRecord> selectByEventId(@Param("eventId") Long eventId);

    @Select("""
            SELECT q.*, e.event_no, e.status AS event_status, e.pollution_type,
                   e.machine_id, e.impact_start_time, e.impact_end_time,
                   m.code AS machine_code, m.name AS machine_name
              FROM quarantine_record q
              JOIN contamination_event e ON e.id = q.event_id
              JOIN machine m ON m.id = e.machine_id
             WHERE q.id = #{id}
            """)
    @ResultMap("quarantineMap")
    QuarantineRecord selectById(@Param("id") Long id);

    @Select("SELECT * FROM quarantine_record WHERE id = #{id} FOR UPDATE")
    @ResultMap("quarantineMap")
    QuarantineRecord selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT id FROM quarantine_record
             WHERE event_id = #{eventId} AND status = 'ACTIVE'
             ORDER BY id
             FOR UPDATE
            """)
    List<Long> selectActiveIdsByEventForUpdate(@Param("eventId") Long eventId);

    @Select("SELECT COUNT(*) FROM quarantine_record WHERE batch_id = #{batchId} AND status = 'ACTIVE'")
    int countActiveByBatch(@Param("batchId") Long batchId);

    @Select("""
            SELECT COUNT(*)
              FROM quarantine_record_rewash qr
              JOIN quarantine_record q ON q.id = qr.quarantine_id
             WHERE qr.rewash_id = #{rewashId} AND q.status = 'ACTIVE'
            """)
    int countActiveQuarantineByRewash(@Param("rewashId") Long rewashId);

    @Select("""
            SELECT COUNT(*)
              FROM quarantine_record_rewash qr
              JOIN quarantine_record q ON q.id = qr.quarantine_id
             WHERE qr.rewash_id = #{rewashId} AND q.status = 'RELEASED'
            """)
    int countReleasedQuarantineByRewash(@Param("rewashId") Long rewashId);

    @Insert("""
            INSERT INTO quarantine_record
                (event_id, batch_id, status, hit_reason, created_by, created_at, released_at)
            VALUES
                (#{eventId}, #{batchId}, #{status}, #{hitReason}, #{createdBy}, #{createdAt}, #{releasedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(QuarantineRecord record);

    @Update("""
            UPDATE quarantine_record
               SET status = 'RELEASED', released_at = NOW()
             WHERE event_id = #{eventId} AND status = 'ACTIVE'
            """)
    int releaseByEvent(@Param("eventId") Long eventId);

    @Insert("""
            INSERT IGNORE INTO quarantine_record_rewash (quarantine_id, rewash_id, created_at)
            VALUES (#{quarantineId}, #{rewashId}, NOW())
            """)
    int linkRewash(@Param("quarantineId") Long quarantineId, @Param("rewashId") Long rewashId);

    @Select("""
            SELECT event_id
              FROM quarantine_record
             WHERE id = #{quarantineId}
             FOR UPDATE
            """)
    Long selectEventIdForUpdate(@Param("quarantineId") Long quarantineId);
}
