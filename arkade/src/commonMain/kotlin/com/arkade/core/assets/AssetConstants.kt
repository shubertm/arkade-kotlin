package com.arkade.core.assets

/** The size, in bytes, of a transaction id/hash. */
const val TX_HASH_SIZE: Int = 32

/** The fixed serialized size, in bytes, of an [AssetId] ([TX_HASH_SIZE] + 2 for the group index). */
const val ASSET_ID_SIZE: Int = 34 // 32 + 2

/** The current version of the asset extension binary format. */
const val ASSET_VERSION = 0x01

/** [AssetGroup] presence bitmask flag indicating an explicit asset id is present. */
const val MASK_ASSET_ID = 0x01

/** [AssetGroup] presence bitmask flag indicating a control asset reference is present. */
const val MASK_CONTROL_ASSET = 0x02

/** [AssetGroup] presence bitmask flag indicating a metadata list is present. */
const val MASK_METADATA = 0x04
