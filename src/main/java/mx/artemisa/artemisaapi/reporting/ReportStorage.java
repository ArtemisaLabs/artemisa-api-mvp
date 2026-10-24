package mx.artemisa.artemisaapi.reporting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

@Component
public class ReportStorage {

    private final Path root;

    public ReportStorage(
            @Value("${reports.storage-directory:./var/reports}") String rootDirectory) {
        this.root = Path.of(rootDirectory).toAbsolutePath().normalize();
    }

    public StoredFile writeZip(UUID tenantId, UUID jobId, ZipWriter writer) throws IOException {
        Path directory = root.resolve(tenantId.toString()).normalize();
        if (!directory.startsWith(root)) {
            throw new IOException("La ruta del reporte no es válida.");
        }
        Files.createDirectories(directory);
        Path finalPath = directory.resolve(jobId + ".zip");
        Path temporaryPath = directory.resolve(jobId + ".zip.tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporaryPath))) {
                writer.write(zip);
            }
            try {
                Files.move(temporaryPath, finalPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            Files.deleteIfExists(temporaryPath);
            throw exception;
        }
        return new StoredFile(
                tenantId + "/" + jobId + ".zip",
                Files.size(finalPath),
                sha256(finalPath));
    }

    public FileSystemResource load(String storageKey) throws IOException {
        Path file = resolve(storageKey);
        if (!Files.isRegularFile(file)) {
            throw new IOException("El archivo del reporte no está disponible.");
        }
        return new FileSystemResource(file);
    }

    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) throws IOException {
        Path file = root.resolve(storageKey).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("La ruta del reporte no es válida.");
        }
        return file;
    }

    private static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    @FunctionalInterface
    public interface ZipWriter {
        void write(ZipOutputStream zip) throws IOException;
    }

    public record StoredFile(String storageKey, long sizeBytes, String sha256) { }
}
