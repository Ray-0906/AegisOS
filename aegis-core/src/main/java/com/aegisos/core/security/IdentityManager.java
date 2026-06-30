package com.aegisos.core.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

public class IdentityManager {

    private final Path homeDir;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public IdentityManager(Path homeDir) {
        this.homeDir = homeDir;
    }

    private void generateIdentityIfMissing(Path keyPath, Path certPath) throws Exception {
        if (Files.exists(keyPath) && Files.exists(certPath)) {
            return;
        }

        Files.createDirectories(keyPath.getParent());

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        long now = System.currentTimeMillis();
        Date startDate = new Date(now);
        Date endDate = Date.from(Instant.ofEpochMilli(now).plus(365, ChronoUnit.DAYS));

        X500Name dnName = new X500Name("CN=aegis-node");
        BigInteger certSerialNumber = BigInteger.valueOf(now);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
                .setProvider("BC").build(keyPair.getPrivate());

        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certBuilder.build(contentSigner));

        try (JcaPEMWriter pemWriter = new JcaPEMWriter(new FileWriter(keyPath.toFile()))) {
            pemWriter.writeObject(keyPair.getPrivate());
        }

        try (JcaPEMWriter pemWriter = new JcaPEMWriter(new FileWriter(certPath.toFile()))) {
            pemWriter.writeObject(certificate);
        }
    }

    private KeyStore loadKeyStore() throws Exception {
        Path securityDir = homeDir.resolve("security");
        Path keyPath = securityDir.resolve("node.key");
        Path certPath = securityDir.resolve("node.crt");

        generateIdentityIfMissing(keyPath, certPath);

        String keyString = Files.readString(keyPath);
        String privateKeyPEM = keyString
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyPEM);

        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = kf.generatePrivate(spec);

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certPath.toFile())) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("node", privateKey, "password".toCharArray(), new Certificate[]{cert});
        return ks;
    }

    public SSLContext getClientContext() throws Exception {
        KeyStore ks = loadKeyStore();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "password".toCharArray());

        javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
            new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
            }
        };

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(kmf.getKeyManagers(), trustAllCerts, new java.security.SecureRandom());
        return context;
    }

    public SSLContext getServerContext() throws Exception {
        return getClientContext();
    }
}
