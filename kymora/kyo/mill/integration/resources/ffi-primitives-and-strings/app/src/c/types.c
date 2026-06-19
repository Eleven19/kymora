#include <math.h>
#include <stdbool.h>
#include <string.h>

int types_add_int(int a, int b) {
  return a + b;
}

long types_mul_long(long a, long b) {
  return a * b;
}

double types_hypot(double a, double b) {
  return sqrt((a * a) + (b * b));
}

bool types_is_even(int value) {
  return (value % 2) == 0;
}

int types_name_length(const char *name) {
  return (int)strlen(name);
}
