package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi
import kyo.test.*

class ContactBindingsTests extends Test[Any]:
  "example struct library passes nested structs, string parameters, and string return fields through Kyo FFI" in {
    import AllowUnsafe.embrace.danger

    val contacts = Ffi.load[ContactBindings]
    val local = Contact(
      id = 100,
      score = 3,
      address = Address(zip = 60606, cityCode = 7)
    )
    val remote = Contact(
      id = 200,
      score = 5,
      address = Address(zip = 10001, cityCode = 9)
    )

    assert(contacts.contactsRouteCode(local) == 60716)
    assert(contacts.contactsIsLocal(local, "Chicago"))
    assert(!contacts.contactsIsLocal(remote, "Chicago"))
    assert(contacts.contactsBadge(local) == Badge(10, "local-contact"))
    assert(contacts.contactsBadge(remote) == Badge(1, "remote-contact"))
  }
