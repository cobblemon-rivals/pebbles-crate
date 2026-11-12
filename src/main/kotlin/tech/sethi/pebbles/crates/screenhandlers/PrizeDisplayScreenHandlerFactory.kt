package tech.sethi.pebbles.crates.screenhandlers

import com.mojang.serialization.Dynamic
import net.minecraft.SharedConstants
import net.minecraft.component.ComponentChanges
import net.minecraft.component.DataComponentTypes
import net.minecraft.datafixer.TypeReferences
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringNbtReader
import net.minecraft.registry.Registries
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import tech.sethi.pebbles.crates.PebblesCrate
import tech.sethi.pebbles.crates.PebblesCrate.server
import tech.sethi.pebbles.crates.lootcrates.CrateConfig
import tech.sethi.pebbles.crates.lootcrates.CrateEventHandler
import tech.sethi.pebbles.crates.lootcrates.Prize
import tech.sethi.pebbles.crates.util.ParseableMessage
import tech.sethi.pebbles.crates.util.ParseableName
import tech.sethi.pebbles.crates.util.setLore

class PrizeDisplayScreenHandlerFactory(private val title: Text, private val crateConfig: CrateConfig) :
    NamedScreenHandlerFactory {
    override fun createMenu(syncId: Int, inv: PlayerInventory, player: PlayerEntity): ScreenHandler {
        val crateItems = crateConfig.prize
        val paged = crateItems.size > 45
        val rows = if (paged) 6 else ((crateItems.size + 8) / 9).coerceIn(1, 6)
        val crateInv = CrateInventory(crateItems, 0, rows)
        // Row pixel offset used by GenericContainerScreenHandler for 6 rows: (rows - 4) * 18
        val rowOffset = (rows - 4) * 18
        val type = when (rows) {
            1 -> ScreenHandlerType.GENERIC_9X1
            2 -> ScreenHandlerType.GENERIC_9X2
            3 -> ScreenHandlerType.GENERIC_9X3
            4 -> ScreenHandlerType.GENERIC_9X4
            5 -> ScreenHandlerType.GENERIC_9X5
            else -> ScreenHandlerType.GENERIC_9X6
        }

        return object : ScreenHandler(type, syncId) {
            var currentPage = 0

            init {
                checkSize(crateInv, rows * 9)
                // Display slots (0-53): canTakeItems=false blocks pickup at MC's own slot level,
                // which keeps client and server in sync without manual cursor correction.
                for (row in 0 until rows) {
                    for (col in 0 until 9) {
                        addSlot(object : Slot(crateInv, col + row * 9, 8 + col * 18, 18 + row * 18) {
                            override fun canTakeItems(playerEntity: PlayerEntity) = false
                            override fun canInsert(stack: ItemStack) = false
                        })
                    }
                }
                // Player main inventory (54-80)
                for (row in 0 until 3) {
                    for (col in 0 until 9) {
                        addSlot(Slot(inv, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + rowOffset + 18))
                    }
                }
                // Hotbar (81-89)
                for (col in 0 until 9) {
                    addSlot(Slot(inv, col, 8 + col * 18, 161 + rowOffset + 18))
                }
            }

            override fun canUse(player: PlayerEntity) = true

            override fun quickMove(player: PlayerEntity, slot: Int): ItemStack = ItemStack.EMPTY

            override fun onSlotClick(slotIndex: Int, button: Int, action: SlotActionType, playerEntity: PlayerEntity) {
                if (paged && slotIndex == 45 && currentPage > 0) {
                    currentPage--
                    crateInv.populateInventory(crateItems, currentPage)
                    sendContentUpdates()
                } else if (paged && slotIndex == 53 && currentPage < (crateItems.size - 1) / 45) {
                    currentPage++
                    crateInv.populateInventory(crateItems, currentPage)
                    sendContentUpdates()
                }
                // Always delegate to super so MC handles its own cursor/acknowledgment
                // sync. The locked slots above prevent any actual item movement.
                super.onSlotClick(slotIndex, button, action, playerEntity)
            }
        }
    }

    override fun getDisplayName(): Text {
        return title
    }
}


class CrateInventory(crateItems: List<Prize>, currentPage: Int, rows: Int = 6) : SimpleInventory(rows * 9) {
    init {
        populateInventory(crateItems, currentPage)
    }

    fun populateInventory(crateItems: List<Prize>, currentPage: Int) {
        clear()
        val itemsPerPage = 45
        val startIndex = currentPage * itemsPerPage
        val endIndex = (startIndex + itemsPerPage).coerceAtMost(crateItems.size)

        val totalWeight = crateItems.sumOf { it.chance }
        for (index in startIndex until endIndex) {
            val prize = crateItems[index]
            var itemStack = ItemStack(Registries.ITEM.get(Identifier.tryParse(prize.material)), prize.amount)
            val parsedName = ParseableName(prize.name).returnMessageAsStyledText()

            val chance = prize.chance.toDouble() / totalWeight.toDouble() * 100
            val roundedChance = String.format("%.2f", chance)

            if (prize.nbt != null) {
                val parsedNbt = StringNbtReader.parse(prize.nbt)

                val isLegacy = parsedNbt.keys.any { !CrateEventHandler.NAMESPACED_KEY_PATTERN.matches(it) }
                if (isLegacy) {
                    val legacyNbt = NbtCompound().apply {
                        putString("id", itemStack.registryEntry.idAsString)
                        putInt("Count", prize.amount)
                        put("tag", parsedNbt)
                    }

                    val updatedNbt = server?.dataFixer?.update(
                        TypeReferences.ITEM_STACK,
                        Dynamic(PebblesCrate.nbtOps, legacyNbt),
                        3700,
                        SharedConstants.getGameVersion().saveVersion.id
                    )?.value

                    itemStack = ItemStack.CODEC.parse(PebblesCrate.nbtOps, updatedNbt).result().orElse(ItemStack.EMPTY)
                } else {
                    val updatedNbt =
                        ComponentChanges.CODEC.parse(PebblesCrate.nbtOps, StringNbtReader.parse(prize.nbt)).result()
                            .orElse(null)
                    itemStack.applyChanges(updatedNbt)
                    itemStack.count = prize.amount
                }
            }

            if (prize.lore != null) {
                val lore = prize.lore
                val parsedPrizeLore = lore.map {
                    val message = it.replace("{chance}", roundedChance).replace("{prize_name}", prize.name)
                    ParseableMessage(message, prizeName = prize.name).returnMessageAsStyledText()
                }
                setLore(itemStack, parsedPrizeLore)
            } else {
                setLore(itemStack, listOf(Text.of("Chance: ${roundedChance}%")))
            }

            setStack(index - startIndex, itemStack.apply {
                set(DataComponentTypes.CUSTOM_NAME, parsedName)
            })
        }

        if (size() < 54) return

        // Fill the bottom row with gray stained glass
        for (i in 45..53) {
            setStack(i, ItemStack(Items.GRAY_STAINED_GLASS_PANE))
        }

        val pageText = Text.of("Page ${currentPage + 1} of ${((crateItems.size - 1) / 45) + 1}")

        if (crateItems.size > 45) {

            // Set the page text
            setStack(52, ItemStack(Items.PAPER).apply { set(DataComponentTypes.CUSTOM_NAME, pageText) })

            // Set the navigation arrows
            setStack(45, ItemStack(Items.ARROW).apply { set(DataComponentTypes.CUSTOM_NAME, Text.of("Previous")) })
            setStack(53, ItemStack(Items.ARROW).apply { set(DataComponentTypes.CUSTOM_NAME, Text.of("Next")) })
        }
    }
}
