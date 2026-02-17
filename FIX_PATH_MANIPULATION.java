// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║        FIX PATH MANIPULATION (FORTIFY)                                     ║
// ║        FileUtil.java ligne 13 — FileOutputStream(filePath)                 ║
// ║        1 seul fichier modifié. 0 nouvelle classe.                          ║
// ╚══════════════════════════════════════════════════════════════════════════════╝


// ════════════════════════════════════════════════════════════════════════════════
//  FIX : IFileService.java — Valider le chemin avant d'écrire
// ════════════════════════════════════════════════════════════════════════════════

// Ajouter les imports :
import java.nio.file.Path;
import java.nio.file.Paths;

    // AVANT :
    @Override
    public void store(String content, String fileName, String folderPath) throws IOException {
        FileUtil.writeToFile(content, folderPath + "/" + fileName);
    }

    // APRÈS :
    @Override
    public void store(String content, String fileName, String folderPath) throws IOException {
        Path baseDir = Paths.get(folderPath).normalize().toAbsolutePath();
        Path resolved = baseDir.resolve(fileName).normalize().toAbsolutePath();

        if (!resolved.startsWith(baseDir)) {
            throw new IOException("Invalid file path: path traversal detected");
        }

        FileUtil.writeToFile(content, resolved.toString());
    }

//  fileName = "rapport.txt"        → /app/output/rapport.txt  → ✅ OK
//  fileName = "../../etc/passwd"   → /etc/passwd              → ❌ BLOQUÉ
