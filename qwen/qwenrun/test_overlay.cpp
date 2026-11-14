#include "overlay.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <vector>

static void put(std::ofstream& f, const void* p, size_t n) { f.write((const char*)p, n); }

int main() {
  const auto path = std::filesystem::temp_directory_path() / "hexanima-qwen-overlay-test.ovl";
  std::vector<uint8_t> block(32, 0x11), expected = block;
  const uint32_t one = 1, three = 3;
  const uint64_t off = 7;
  const uint8_t payload[3] = {0xA1, 0xB2, 0xC3};
  expected[7] = payload[0]; expected[8] = payload[1]; expected[9] = payload[2];
  {
    std::ofstream f(path, std::ios::binary); put(f, &one, 4); put(f, &off, 8); put(f, &three, 4); put(f, payload, 3);
  }
  assert(qwen_apply_overlay(path.string(), block.data(), block.size()));
  assert(block == expected);

  // An invalid later record must not leave the earlier valid patch partially applied.
  block.assign(32, 0x11);
  const uint32_t two = 2;
  const uint64_t bad_off = 31;
  {
    std::ofstream f(path, std::ios::binary); put(f, &two, 4);
    put(f, &off, 8); put(f, &three, 4); put(f, payload, 3);
    put(f, &bad_off, 8); put(f, &three, 4); put(f, payload, 3);
  }
  assert(!qwen_apply_overlay(path.string(), block.data(), block.size()));
  assert(block == std::vector<uint8_t>(32, 0x11));
  std::filesystem::remove(path);
}
