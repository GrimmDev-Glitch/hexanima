#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

// Sparse overlay format: u32 record count, then u64 offset + u32 length + bytes.
// Validate the entire file before mutating the streamed model block.
inline bool qwen_apply_overlay(const std::string& path, uint8_t* dst, size_t capacity) {
  FILE* f = fopen(path.c_str(), "rb");
  if (!f) return false;
  struct stat st; uint32_t count = 0;
  if (fstat(fileno(f), &st) || st.st_size < 4 ||
      fread(&count, 4, 1, f) != 1 || count > (uint64_t)(st.st_size - 4) / 12) {
    fclose(f); return false;
  }
  long payload = 4;
  for (uint32_t i = 0; i < count; i++) {
    uint64_t offset; uint32_t length;
    if (fread(&offset, 8, 1, f) != 1 || fread(&length, 4, 1, f) != 1 ||
        !length || offset > capacity || length > capacity - offset) {
      fclose(f); return false;
    }
    long pos = ftell(f);
    if (pos < 0 || (uint64_t)pos + length > (uint64_t)st.st_size) {
      fclose(f); return false;
    }
    payload = pos + (long)length;
    if (payload < 0 || fseek(f, payload, SEEK_SET)) { fclose(f); return false; }
  }
  if (payload != st.st_size || fseek(f, 4, SEEK_SET)) { fclose(f); return false; }
  for (uint32_t i = 0; i < count; i++) {
    uint64_t offset; uint32_t length;
    if (fread(&offset, 8, 1, f) != 1 || fread(&length, 4, 1, f) != 1 ||
        fread(dst + offset, 1, length, f) != length) { fclose(f); return false; }
  }
  fclose(f);
  return true;
}
