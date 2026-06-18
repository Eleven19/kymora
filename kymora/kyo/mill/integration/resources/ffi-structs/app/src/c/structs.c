#include <stdint.h>

typedef struct {
  int32_t x;
  int32_t y;
} point_t;

typedef struct {
  point_t a;
  point_t b;
} line_t;

#if defined(__GNUC__) || defined(__clang__)
typedef struct __attribute__((packed)) {
  int32_t tag;
  int32_t value;
} packed_t;
#else
typedef struct {
  int32_t tag;
  int32_t value;
} packed_t;
#endif

int32_t structs_line_sum(const line_t *line) {
  return line->a.x + line->a.y + line->b.x + line->b.y;
}

int32_t structs_packed_compute(const packed_t *packed) {
  return packed->tag == 1 ? packed->value * 10 : packed->value;
}

int32_t structs_pair(int32_t a, int32_t b, int32_t *out_product) {
  *out_product = a * b;
  return a + b;
}

static const char *status_ok = "OK";
static const char *status_err = "ERR";

int32_t structs_status(int32_t code, const char **out_message) {
  *out_message = code == 0 ? status_ok : status_err;
  return code;
}
