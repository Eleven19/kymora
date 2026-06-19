int buffers_sum(int *values, int len) {
  int total = 0;
  for (int i = 0; i < len; i++) total += values[i];
  return total;
}

void buffers_increment(int *values, int len) {
  for (int i = 0; i < len; i++) values[i] += 1;
}
