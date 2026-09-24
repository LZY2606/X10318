package com.gsb.fea;

import com.gsb.fea.web.ApiState;
import com.gsb.fea.web.WebServer;

import java.nio.file.Path;

/**
 * Sparse FEM assembly ledger entry point.
 *   ./gradlew run --args='--host 127.0.0.1 --port 5399'
 */
public final class Main {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 5399;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) host = args[i + 1];
            if ("--port".equals(args[i])) port = Integer.parseInt(args[i + 1]);
        }
        Path dbFile = Path.of("build", "fea-ledger.db");
        ApiState state = new ApiState(dbFile);
        state.ensureReplayed();
        new WebServer(state).start(host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(state::close));
        System.out.println("FEA sparse assembly ledger: http://" + host + ":" + port);
    }
}
