package com.aegisos.cli.commands;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.KeyStore;
import com.aegisos.core.util.HexUtil;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "info", description = "Show this node's identity and configuration.")
public final class InfoCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--home", description = "Node home directory (default ~/.aegis).")
    Path home;

    @Override
    public Integer call() {
        KeyStore keyStore = home != null ? new KeyStore(home) : KeyStore.defaultStore();
        IdentityService identity = IdentityService.bootstrap(keyStore);
        System.out.println("Node ID    : " + identity.nodeId().toHex());
        System.out.println("Public key : " + HexUtil.encode(identity.publicKey()));
        return 0;
    }
}
