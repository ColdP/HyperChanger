// Adapted from HyperModifier (GPL-3.0), ABI verification only.
#pragma once
#include <cstddef>
#include <cstdint>
#include <iterator>
namespace schema_abi {
struct Function { const char *name; uint32_t size; uint64_t hash; };
struct Edge { uint16_t function, word; int16_t target; const char *symbol; };
struct Contract {
    const Function *functions;
    size_t functionCount;
    const Edge *edges;
    size_t edgeCount, rootCount;
};
}
