#include <stdbool.h>
#include <stdint.h>
#include <string.h>

typedef struct {
  int32_t zip;
  int32_t city_code;
} address_t;

typedef struct {
  int32_t id;
  int32_t score;
  address_t address;
} contact_t;

int32_t contacts_route_code(const contact_t *contact) {
  return contact->id + contact->score + contact->address.zip + contact->address.city_code;
}

bool contacts_is_local(const contact_t *contact, const char *city) {
  return contact->address.zip == 60606 && strcmp(city, "Chicago") == 0;
}

static const char *local_label = "local-contact";
static const char *remote_label = "remote-contact";

int32_t contacts_badge(const contact_t *contact, const char **out_label) {
  bool local = contact->address.zip == 60606;
  *out_label = local ? local_label : remote_label;
  return local ? 10 : 1;
}
