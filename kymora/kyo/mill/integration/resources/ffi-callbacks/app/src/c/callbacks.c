#include <stdlib.h>

typedef int (*compare_fn)(int, int);
typedef void (*handler_fn)(int);

static compare_fn current_compare;
static handler_fn retained_handler;

static int compare_adapter(const void *a, const void *b) {
  int left = *(const int *)a;
  int right = *(const int *)b;
  return current_compare(left, right);
}

int callbacks_sum_pairs(int (*callback)(int, int), int count) {
  int total = 0;
  for (int i = 0; i < count; i++) total += callback(i, i * 2);
  return total;
}

void callbacks_sort(int *values, int len, compare_fn compare) {
  current_compare = compare;
  qsort(values, (size_t)len, sizeof(int), compare_adapter);
}

void callbacks_register(handler_fn callback) {
  retained_handler = callback;
}

void callbacks_fire(int value) {
  if (retained_handler != NULL) retained_handler(value);
}
