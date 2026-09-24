package gsb.fea.casepack;

import gsb.fea.util.Json;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CasePackLoader {

    public static final String RESOURCE = "/casepack/fea-sparse-ledger.json";
    public static final Path FILE_PATH = Path.of("casepack/fea-sparse-ledger.json");

    public static CasePack load() {
        if (Files.exists(FILE_PATH)) {
            return Json.readFile(FILE_PATH, CasePack.class);
        }
        InputStream in = CasePackLoader.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("case pack not found at " + FILE_PATH
                    + " or classpath " + RESOURCE);
        }
        return Json.read(in, CasePack.class);
    }

    public static CasePack load(Path path) {
        return Json.readFile(path, CasePack.class);
    }

    private CasePackLoader() {
    }
}
