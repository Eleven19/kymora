package example

import kyo.AllowUnsafe
import kyo.ffi.Ffi

case class Address(zip: Int, cityCode: Int)
case class Contact(id: Int, score: Int, address: Address)
case class Badge(priority: Int, label: String)

trait ContactBindings extends Ffi:
  def contactsRouteCode(contact: Contact)(using AllowUnsafe): Int
  def contactsIsLocal(contact: Contact, city: String)(using AllowUnsafe): Boolean
  def contactsBadge(contact: Contact)(using AllowUnsafe): Badge

object ContactBindings extends Ffi.Config(library = "contacts")
