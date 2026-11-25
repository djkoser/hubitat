package com.hubitat.zigbee

/**
 * Compile-time stand-in for the hub's com.hubitat.zigbee.DataType (Zigbee ZCL data type
 * codes), which hubitat_ci 0.17 doesn't provide. Values match the ZCL specification.
 */
class DataType {
    static final int BOOLEAN = 0x10
    static final int BITMAP8 = 0x18
    static final int BITMAP16 = 0x19
    static final int UINT8 = 0x20
    static final int UINT16 = 0x21
    static final int UINT24 = 0x22
    static final int UINT32 = 0x23
    static final int INT8 = 0x28
    static final int INT16 = 0x29
    static final int INT32 = 0x2B
    static final int ENUM8 = 0x30
    static final int ENUM16 = 0x31
    static final int STRING_OCTET = 0x41
    static final int STRING_CHAR = 0x42
}
