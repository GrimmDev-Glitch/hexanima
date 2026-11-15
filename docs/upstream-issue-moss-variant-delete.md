**Title:** Deleting a MOSS variant (RealSkin / Harem) also deletes the base MOSS-Image text encoder and VAE

Hi ayanoby, thank you for HexAnima! While working on a Snapdragon 8 Gen 2 fork I found a data-loss bug that I think is also in your current `main` (1.6.0).

**What happens**

1. Install MOSS-Image 5B and then one of the MOSS variants (MiaoMiao RealSkin MOSS 1.0 or Harem MOSS 1.1).
2. In Models, delete the variant.
3. MOSS-Image 5B is still listed as installed, but its `te/` and `vae/` folders are now empty, so it can no longer generate until it is downloaded again.

**Why**

- `Moss.variantRs()` creates `files/moss/v<sub>/` with symbolic links to the base model's files and folders: `te`, `vae`, `embd.q4k`, the tokenizer files and `glue8.bin` (`Moss.java`, around line 31: `Files.createSymbolicLink(p, t.toPath())`).
- When the variant is deleted, `Models.delete()` calls `deleteTree(new File(dir, "v" + Moss.sub(id)))` (`Models.java`, line 248).
- `deleteTree` recurses with `File.listFiles()`. For a symbolic link to a directory, `listFiles()` lists the *target's* contents, so `v<sub>/te` and `v<sub>/vae` are walked into and every file of the base `te/` and `vae/` is deleted. Links to plain files are fine: `delete()` on them only removes the link.

**Fix** (one line, what we use in the fork, commit `02c19b5`)

```java
static void deleteTree(File f) {
    if (java.nio.file.Files.isSymbolicLink(f.toPath())) { f.delete(); return; }   // remove the link itself, never what it points to
    File[] fs = f.listFiles(); if (fs != null) for (File c : fs) deleteTree(c); f.delete();
}
```

The same `deleteTree` pattern is used in other places (for example a copy in `MossLora`), so it may be worth applying it there too.

Fork for reference: https://github.com/GrimmDev-Glitch/hexanima (Snapdragon 8 Gen 2 / Hexagon V73 port, all credit for HexAnima to you).

---

*Кратко по-русски:* при удалении варианта MOSS (RealSkin / Harem) `deleteTree` проходит по символическим ссылкам в `v<sub>/` и удаляет файлы базовой модели MOSS-Image (`te/`, `vae/`). Исправление — не заходить внутрь ссылок (`Files.isSymbolicLink` → `delete()`), см. код выше.
