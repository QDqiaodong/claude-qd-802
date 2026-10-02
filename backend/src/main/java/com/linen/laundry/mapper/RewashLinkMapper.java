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

import com.linen.laundry.domain.RewashLink;

@Mapper
public interface RewashLinkMapper {

    @Select("""
            SELECT r.*
              FROM rewash_link r
              JOIN quarantine_record_rewash qr ON qr.rewash_id = r.id
             WHERE qr.quarantine_id = #{quarantineId}
             ORDER BY r.id
            """)
    @Results(id = "rewashMap", value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "sourceBatchId", column = "source_batch_id"),
            @Result(property = "rewashBatchId", column = "rewash_batch_id"),
            @Result(property = "operatorName", column = "operator_name"),
            @Result(property = "status", column = "status"),
            @Result(property = "failureReason", column = "failure_reason"),
            @Result(property = "startedAt", column = "started_at"),
            @Result(property = "finishedAt", column = "finished_at"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "sourceBatch", column = "source_batch_id",
                    one = @One(
                            select = "com.linen.laundry.mapper.WashBatchMapper.selectById")),
            @Result(property = "rewashBatch", column = "rewash_batch_id",
                    one = @One(
                            select = "com.linen.laundry.mapper.WashBatchMapper.selectById"))
    })
    List<RewashLink> selectByQuarantineId(@Param("quarantineId") Long quarantineId);

    @Select("""
            SELECT r.*
              FROM rewash_link r
              JOIN quarantine_record_rewash qr ON qr.rewash_id = r.id
             WHERE qr.quarantine_id = #{quarantineId}
             ORDER BY r.id
             FOR UPDATE
            """)
    @ResultMap("rewashMap")
    List<RewashLink> selectByQuarantineIdForUpdate(@Param("quarantineId") Long quarantineId);

    @Select("SELECT * FROM rewash_link WHERE id = #{id}")
    @ResultMap("rewashMap")
    RewashLink selectById(@Param("id") Long id);

    @Select("""
            SELECT *
              FROM rewash_link
             WHERE rewash_batch_id = #{batchId}
            """)
    @ResultMap("rewashMap")
    RewashLink selectByRewashBatchId(@Param("batchId") Long batchId);

    @Select("""
            SELECT *
              FROM rewash_link
             WHERE source_batch_id = #{sourceBatchId} AND status = 'RUNNING'
             ORDER BY id
             FOR UPDATE
            """)
    @ResultMap("rewashMap")
    List<RewashLink> selectActiveBySourceForUpdate(@Param("sourceBatchId") Long sourceBatchId);

    @Insert("""
            INSERT INTO rewash_link
                (source_batch_id, rewash_batch_id, operator_name, status, started_at, created_at)
            VALUES
                (#{sourceBatchId}, #{rewashBatchId}, #{operatorName}, #{status}, #{startedAt}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(RewashLink link);

    @Update("""
            UPDATE rewash_link
               SET status = 'DONE', finished_at = NOW()
             WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markDone(@Param("id") Long id);

    @Update("""
            UPDATE rewash_link
               SET status = 'FAILED', failure_reason = #{reason}, finished_at = NOW()
             WHERE id = #{id} AND status = 'RUNNING'
            """)
    int markFailed(@Param("id") Long id, @Param("reason") String reason);

    @Update("""
            UPDATE rewash_link
               SET status = 'DELETED', finished_at = NOW()
             WHERE id = #{id} AND status IN ('RUNNING', 'FAILED')
            """)
    int markDeleted(@Param("id") Long id);
}
