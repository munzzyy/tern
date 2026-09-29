package io.github.munzzyy.stamp.ui.apps

import io.github.munzzyy.stamp.ui.BackStack
import io.github.munzzyy.stamp.ui.Tab

/** Where "Send from a phone" on the empty list leads. It is the Add screen until the route of the handoff is put in here. */
fun openHandoff(stack: BackStack) = stack.select(Tab.ADD)
