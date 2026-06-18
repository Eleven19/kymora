#include <stdbool.h>
#include <string.h>

int textkit_count_words(const char *text) {
  int count = 0;
  bool in_word = false;

  for (const char *p = text; *p != '\0'; p++) {
    bool is_space = *p == ' ' || *p == '\n' || *p == '\t';
    if (is_space) {
      in_word = false;
    } else if (!in_word) {
      count++;
      in_word = true;
    }
  }

  return count;
}

bool textkit_has_prefix(const char *text, const char *prefix) {
  return strncmp(text, prefix, strlen(prefix)) == 0;
}

int textkit_shared_prefix_length(const char *left, const char *right) {
  int count = 0;
  while (left[count] != '\0' && right[count] != '\0' && left[count] == right[count]) {
    count++;
  }
  return count;
}

double textkit_score_title(const char *title, const char *keyword) {
  int title_words = textkit_count_words(title);
  int keyword_len = (int)strlen(keyword);
  return textkit_has_prefix(title, keyword) ? title_words + (keyword_len / 10.0) : title_words / 2.0;
}
