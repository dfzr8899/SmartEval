package io.smarteval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Database {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    final ObjectMapper json;
    private final boolean h2;
    Database(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,
             @Value("${spring.datasource.url}") String jdbcUrl){
        this.jdbc=jdbc;this.json=json;this.transactions=new TransactionTemplate(manager);
        this.h2 = jdbcUrl.startsWith("jdbc:h2:");
    }
    void transaction(Runnable writes){transactions.executeWithoutResult(status->writes.run());}
    <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }

    int write(String sql, Object... args) { return jdbc.update(sql, params(args)); }
    void batch(String sql,List<Object[]> rows){
        if(!rows.isEmpty())jdbc.batchUpdate(sql,rows.stream().map(Database::params).toList());
    }
    <T> List<T> list(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, params(args));
    }
    <T> T one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows=list(sql,mapper,args);
        return rows.isEmpty()?null:rows.getFirst();
    }
    <T> T required(String sql, RowMapper<T> mapper, Object... args) {
        T result = one(sql, mapper, args);
        if (result == null) throw Api.missing();
        return result;
    }

    static UUID uuid(ResultSet row, String field) throws SQLException { return Api.uuid(row.getBytes(field)); }
    static Instant time(ResultSet row, String field) throws SQLException {
        LocalDateTime value = row.getObject(field, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    Object jsonValue(Object value) {
        String encoded = encode(value);
        // H2 interprets character parameters as JSON strings and binary parameters as JSON documents.
        return h2 ? encoded.getBytes(StandardCharsets.UTF_8) : encoded;
    }
    JsonNode parse(String value) {
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    void audit(UUID tenant, UUID user, String action, String targetType, UUID target, Object details) {
        write("INSERT INTO audit_log(id,tenant_id,actor_type,actor_id,action,target_type,target_id,trace_id,details,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(),tenant,"USER",user,action,targetType,target,UUID.randomUUID(),jsonValue(details),Instant.now());
    }

    private static Object[] params(Object[] args) {
        return java.util.Arrays.stream(args).map(value -> {
            if (value instanceof UUID id) return Api.key(id);
            if (value instanceof Instant time) return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
            return value;
        }).toArray();
    }
}
