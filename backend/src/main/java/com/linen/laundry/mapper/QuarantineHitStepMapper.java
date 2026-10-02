package com.linen.laundry.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import com.linen.laundry.domain.QuarantineHitStep;

@Mapper
public interface QuarantineHitStepMapper {

    @Select("""
            SELECT *
              FROM quarantine_hit_step
             WHERE quarantine_id = #{quarantineId}
             ORDER BY step_order
            """)
    @Results(id = "hitStepMap", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "quarantineId", column = "quarantine_id"),
            @Result(property = "batchStepId", column = "batch_step_id"),
            @Result(property = "stepCode", column = "step_code"),
            @Result(property = "stepName", column = "step_name"),
            @Result(property = "stepOrder", column = "step_order"),
            @Result(property = "stepStartTime", column = "step_start_time"),
            @Result(property = "stepEndTime", column = "step_end_time"),
            @Result(property = "overlapStartTime", column = "overlap_start_time"),
            @Result(property = "overlapEndTime", column = "overlap_end_time"),
            @Result(property = "overlapSeconds", column = "overlap_seconds"),
            @Result(property = "reason", column = "reason")
    })
    List<QuarantineHitStep> selectByQuarantineId(@Param("quarantineId") Long quarantineId);

    @Select("""
            SELECT s.id AS batch_step_id,
                   s.step_code, s.step_name, s.step_order,
                   s.start_time AS step_start_time,
                   COALESCE(s.end_time, NOW()) AS step_end_time,
                   GREATEST(s.start_time, #{impactStart}) AS overlap_start_time,
                   LEAST(COALESCE(s.end_time, NOW()), #{impactEnd}) AS overlap_end_time,
                   TIMESTAMPDIFF(
                       SECOND,
                       GREATEST(s.start_time, #{impactStart}),
                       LEAST(COALESCE(s.end_time, NOW()), #{impactEnd})
                   ) AS overlap_seconds
              FROM batch_step s
              JOIN wash_batch b ON b.id = s.batch_id
             WHERE s.machine_id = #{machineId}
               AND s.start_time <= #{impactEnd}
               AND COALESCE(s.end_time, NOW()) >= #{impactStart}
             ORDER BY s.start_time, s.id
            """)
    @ResultMap("hitStepMap")
    List<QuarantineHitStep> findOverlappingSteps(@Param("machineId") Long machineId,
                                                 @Param("impactStart") java.time.LocalDateTime impactStart,
                                                 @Param("impactEnd") java.time.LocalDateTime impactEnd);

    @Insert("""
            INSERT INTO quarantine_hit_step
                (quarantine_id, batch_step_id, step_code, step_name, step_order,
                 step_start_time, step_end_time, overlap_start_time, overlap_end_time,
                 overlap_seconds, reason)
            VALUES
                (#{quarantineId}, #{batchStepId}, #{stepCode}, #{stepName}, #{stepOrder},
                 #{stepStartTime}, #{stepEndTime}, #{overlapStartTime}, #{overlapEndTime},
                 #{overlapSeconds}, #{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(QuarantineHitStep hitStep);
}
