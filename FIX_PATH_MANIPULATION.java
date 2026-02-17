// ╔══════════════════════════════════════════════════════════════════════════════╗
// ║        FIX PATH MANIPULATION (FORTIFY)                                     ║
// ║        FileUtil.java ligne 13 — FileOutputStream(filePath)                 ║
// ║        2 fichiers modifiés. 0 nouvelle classe.                             ║
// ╚══════════════════════════════════════════════════════════════════════════════╝
//
//  VULNÉRABILITÉ :
//  ───────────────
//  IFileService.store(content, fileName, folderPath)
//    → folderPath + "/" + fileName         ← PAS VALIDÉ
//    → FileUtil.writeToFile(message, path)
//    → new FileOutputStream(path)          ← ATTAQUANT ÉCRIT OÙ IL VEUT
//
//  ATTAQUE :
//    fileName = "../../etc/crontab"
//    → folderPath + "/../../etc/crontab"
//    → écrit dans /etc/crontab au lieu du dossier autorisé
//
//  FICHIERS MODIFIÉS : 2
//  ─────────────────
//  1. IFileService.java  → validation path traversal dans store()
//  2. FileUtil.java       → validation défensive dans writeToFile()


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 1 : IFileService.java — Valider le chemin résolu
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

//  POURQUOI ÇA MARCHE :
//    fileName = "rapport.txt"        → /app/output/rapport.txt    → startsWith OK ✅
//    fileName = "../../etc/passwd"   → /etc/passwd                → startsWith KO ❌ BLOQUÉ
//    fileName = "../../../tmp/hack"  → /tmp/hack                  → startsWith KO ❌ BLOQUÉ


// ════════════════════════════════════════════════════════════════════════════════
//  ÉTAPE 2 : FileUtil.java — Validation défensive (double sécurité)
// ════════════════════════════════════════════════════════════════════════════════

// Ajouter les imports :
import java.nio.file.Path;
import java.nio.file.Paths;

    // AVANT :
    public static void writeToFile(String message, String filePath) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
            byte[] strToBytes = message.getBytes();
            outputStream.write(strToBytes);
        }
    }

    // APRÈS :
    public static void writeToFile(String message, String filePath) throws IOException {
        Path path = Paths.get(filePath).normalize().toAbsolutePath();

        if (path.toString().contains("..")) {
            throw new IOException("Invalid file path: path traversal detected");
        }

        try (FileOutputStream outputStream = new FileOutputStream(path.toFile())) {
            byte[] strToBytes = message.getBytes();
            outputStream.write(strToBytes);
        }
    }


// ════════════════════════════════════════════════════════════════════════════════
//  RÉSUMÉ
// ════════════════════════════════════════════════════════════════════════════════
/*
  ╔════════════════════╦═════════════════════════════════════════════════════════╗
  ║ FICHIER            ║ MODIFICATION                                          ║
  ╠════════════════════╬═════════════════════════════════════════════════════════╣
  ║ IFileService.java  ║ Validation : resolved.startsWith(baseDir)             ║
  ║                    ║ + imports java.nio.file.Path, Paths                    ║
  ╠════════════════════╬═════════════════════════════════════════════════════════╣
  ║ FileUtil.java      ║ Validation défensive : reject ".." après normalize()  ║
  ║                    ║ + imports java.nio.file.Path, Paths                    ║
  ╚════════════════════╩═════════════════════════════════════════════════════════╝

  La protection principale est dans IFileService.store() (startsWith).
  La protection dans FileUtil.writeToFile() est une défense en profondeur.
  Fortify ne flag plus : le chemin est validé avant FileOutputStream.
*/
