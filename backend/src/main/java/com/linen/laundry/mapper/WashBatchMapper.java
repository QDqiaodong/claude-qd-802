package com.linen.laundry.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.One;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import com.linen.laundry.domain.BatchStep;
import com.linen.laundry.domain.LinenCategory;
import com.linen.laundry.domain.Machine;
import com.linen.laundry.domain.WashBatch;
import com.linen.laundry.dto.StepStat;

/**
 * 洗涤批次：一条查询带出工序流转记录（@Many）、品类（@One）与设备（@One）。
 */
@Mapper
public interface WashBatchMapper {

    @Select("""
            SELECT b.*,
                   c.name       AS category_name,
                   m.name       AS machine_name,
                   m.code       AS machine_code,
                   ROUND(b.current_step_order * 100.0 / 8, 0) AS progress
              FROM wash_batch b
              LEFT JOIN linen_category c ON c.id = b.category_id
              LEFT JOIN machine m        ON m.id = b.machine_id
             ORDER BY FIELD(b.status, 'RUNNING', 'HOLD', 'DONE'), b.start_time DESC
            """)
    @Results(id = "batchMap", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "batchNo", column = "batch_no"),
            @Result(property = "categoryId", column = "category_id"),
            @Result(property = "weightKg", column = "weight_kg"),
            @Result(property = "currentStep", column = "current_step"),
            @Result(property = "currentStepOrder", column = "current_step_order"),
            @Result(property = "detergentMl", column = "detergent_ml"),
            @Result(property = "waterTemp", column = "water_temp"),
            @Result(property = "machineId", column = "machine_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "finishTime", column = "finish_time"),
            @Result(property = "categoryName", column = "category_name"),
            @Result(property = "machineName", column = "machine_name"),
            @Result(property = "machineCode", column = "machine_code"),
            @Result(property = "progress", column = "progress"),
            @Result(property = "steps", column = "id",
                    many = @Many(select = "com.linen.laundry.mapper.WashBatchMapper.selectSteps")),
            @Result(property = "category", column = "category_id",
                    one = @One(select = "com.linen.laundry.mapper.LinenCategoryMapper.selectById")),
            @Result(property = "machine", column = "machine_id",
                    one = @One(select = "com.linen.laundry.mapper.MachineMapper.selectById"))
    })
    List<WashBatch> selectAll();

    /** 嵌套 @Many：批次工序流转记录 */
    @Select("SELECT * FROM batch_step WHERE batch_id = #{batchId} ORDER BY step_order")
    List<BatchStep> selectSteps(@Param("batchId") Long batchId);

    @Select("""
            SELECT b.*,
                   c.name AS category_name,
                   m.name AS machine_name,
                   m.code AS machine_code,
                   ROUND(b.current_step_order * 100.0 / 8, 0) AS progress
              FROM wash_batch b
              LEFT JOIN linen_category c ON c.id = b.category_id
              LEFT JOIN machine m        ON m.id = b.machine_id
             WHERE b.id = #{id}
            """)
    @ResultMap("batchMap")
    WashBatch selectById(@Param("id") Long id);

    /** 供品类 @Many 嵌套调用 */
    @Select("""
            SELECT b.*,
                   c.name AS category_name,
                   m.name AS machine_name,
                   m.code AS machine_code,
                   ROUND(b.current_step_order * 100.0 / 8, 0) AS progress
              FROM wash_batch b
              LEFT JOIN linen_category c ON c.id = b.category_id
              LEFT JOIN machine m        ON m.id = b.machine_id
             WHERE b.category_id = #{categoryId}
             ORDER BY b.start_time DESC
             LIMIT 4
            """)
    @ResultMap("batchMap")
    List<WashBatch> selectByCategoryId(@Param("categoryId") Long categoryId);

    /** 隧道视图：动态条件（状态 / 停留工序 / 品类）走 @SelectProvider */
    @SelectProvider(type = WashBatchSqlProvider.class, method = "tunnel")
    @ResultMap("batchMap")
    List<WashBatch> tunnel(@Param("status") String status,
                           @Param("stepCode") String stepCode,
                           @Param("categoryId") Long categoryId);

    @Insert("""
            INSERT INTO wash_batch (batch_no, category_id, weight_kg, current_step, current_step_order,
                                    detergent_ml, water_temp, machine_id, operator, status,
                                    start_time, finish_time, remark)
            VALUES (#{batchNo}, #{categoryId}, #{weightKg}, #{currentStep}, #{currentStepOrder},
                    #{detergentMl}, #{waterTemp}, #{machineId}, #{operator}, #{status},
                    #{startTime}, #{finishTime}, #{remark})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(WashBatch batch);

    /** 推进工序：更新当前工序与状态 */
    @Update("""
            UPDATE wash_batch
               SET current_step = #{currentStep}, current_step_order = #{currentStepOrder},
                   water_temp = IFNULL(#{waterTemp}, water_temp),
                   detergent_ml = IFNULL(#{detergentMl}, detergent_ml),
                   operator = IFNULL(#{operator}, operator),
                   status = #{status},
                   finish_time = #{finishTime}
             WHERE id = #{id}
            """)
    int advance(WashBatch batch);

    @Insert("""
            INSERT INTO batch_step (batch_id, step_code, step_name, step_order, water_temp,
                                    detergent_ml, operator, status, start_time, end_time)
            VALUES (#{batchId}, #{stepCode}, #{stepName}, #{stepOrder}, #{waterTemp},
                    #{detergentMl}, #{operator}, #{status}, #{startTime}, #{endTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertStep(BatchStep step);

    /** 把上一道工序收口为 DONE */
    @Update("""
            UPDATE batch_step
               SET status = 'DONE', end_time = NOW()
             WHERE batch_id = #{batchId} AND step_code = #{stepCode}
            """)
    int finishStep(@Param("batchId") Long batchId, @Param("stepCode") String stepCode);

    @Delete("DELETE FROM batch_step WHERE batch_id = #{batchId}")
    int deleteSteps(@Param("batchId") Long batchId);

    @Delete("DELETE FROM wash_batch WHERE id = #{id}")
    int delete(@Param("id") Long id);

    /** 工序站位统计：当前停在各工序的批次与重量 */
    @Select("""
            SELECT b.current_step                        AS step_code,
                   MIN(b.current_step_order)             AS step_order,
                   COUNT(*)                              AS batch_count,
                   IFNULL(ROUND(SUM(b.weight_kg), 0), 0) AS total_weight
              FROM wash_batch b
             WHERE b.status = 'RUNNING'
             GROUP BY b.current_step
             ORDER BY step_order
            """)
    List<StepStat> stepStats();

    @Select("""
            SELECT COUNT(*)                                                   AS batch_total,
                   SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END)        AS running_batch,
                   SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END)           AS done_batch,
                   IFNULL(ROUND(SUM(CASE WHEN status = 'RUNNING' THEN weight_kg ELSE 0 END), 0), 0) AS running_weight
              FROM wash_batch
            """)
    Map<String, Object> summary();
}
