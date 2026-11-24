package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V3_1__backfill_grading_asset_references extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var json = new ObjectMapper();
        var connection = context.getConnection();
        try (var read = connection.prepareStatement("SELECT id,tenant_id,submission_input FROM grading_job");
             var insert = connection.prepareStatement("""
                     INSERT INTO grading_job_asset(job_id,asset_id)
                     SELECT ?,a.id FROM grading_asset a WHERE a.id=? AND a.tenant_id=?
                     AND NOT EXISTS (SELECT 1 FROM grading_job_asset r WHERE r.job_id=? AND r.asset_id=a.id)
                     """);
             var jobs = read.executeQuery()) {
            while (jobs.next()) {
                byte[] job = jobs.getBytes("id");
                for (var material : json.readTree(jobs.getString("submission_input")).path("materials")) {
                    UUID id = UUID.fromString(material.path("asset_id").asText());
                    byte[] asset = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                            .putLong(id.getLeastSignificantBits()).array();
                    insert.setBytes(1,job);
                    insert.setBytes(2,asset);
                    insert.setBytes(3,jobs.getBytes("tenant_id"));
                    insert.setBytes(4,job);
                    insert.executeUpdate();
                }
            }
        }
    }
}
