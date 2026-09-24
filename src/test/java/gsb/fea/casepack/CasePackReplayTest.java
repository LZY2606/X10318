package gsb.fea.casepack;

import gsb.fea.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The offline case pack must replay deterministically with every expectation met. */
class CasePackReplayTest {

    @TempDir
    Path tempDir;

    @Test
    void replayMatchesAllExpectations() {
        CasePack pack = CasePackLoader.load();
        try (Store store = Store.open(tempDir.resolve("replay.db"))) {
            CasePackRunner.ReplayReport report = CasePackRunner.run(pack, store);
            if (!report.ok) {
                StringBuilder message = new StringBuilder("replay failures:\n");
                for (CasePackRunner.StepReport step : report.steps) {
                    for (String failure : step.failures) {
                        message.append("step ").append(step.n).append(" (")
                                .append(step.title).append("): ").append(failure).append('\n');
                    }
                }
                fail(message.toString());
            }
            assertTrue(report.ok);
        }
    }
}
