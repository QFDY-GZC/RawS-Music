package com.rawsmusic.module.player.devicecontrol.bluetooth

internal fun BluetoothGattInventory.toDiagnosticLines(): List<String> = buildList {
    services.forEachIndexed { serviceIndex, service ->
        val instance = services.take(serviceIndex).count { it.uuid == service.uuid }
        add(
            "bt.service[$serviceIndex] uuid=${service.uuid} instance=$instance type=${service.type} included=${service.includedServiceUuids.joinToString(",")}",
        )
        service.characteristics.forEachIndexed { characteristicIndex, characteristic ->
            add(
                "bt.char[$serviceIndex:$characteristicIndex] uuid=${characteristic.uuid} props=${characteristic.properties.sortedBy { it.name }.joinToString("|")} perms=${characteristic.permissions} descriptors=${characteristic.descriptors.joinToString(",") { it.uuid.toString() }}",
            )
        }
    }
}
