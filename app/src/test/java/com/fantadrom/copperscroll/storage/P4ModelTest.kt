package com.fantadrom.copperscroll.storage

import com.fantadrom.copperscroll.storage.cards.*
import com.fantadrom.copperscroll.storage.format.*
import com.fantadrom.copperscroll.storage.transaction.*
import com.fantadrom.copperscroll.storage.vfs.*
import com.fantadrom.copperscroll.storage.runtimechecks.*
import org.junit.Test
import java.util.Random
import java.nio.ByteBuffer

/** Independent imperative model: no repository call computes expected command effects. */
class P4ModelTest {
    private data class Item(val type: Int,var parent: Bytes,var name: String,val created: Long,
        var content: Long,var metadata: Long,var revision: ULong = 1u,var value: String = "",
        var pinned: Boolean = false,var pin: ULong? = null)
    private fun id(n: Int) = Bytes(ByteBuffer.allocate(16).putInt(0x5034).putInt(n).array())
    @Test fun seededVfsCardSequencesAgainstIndependentModel() {
        for(seed in listOf(328705L,328706L)) P4Fixtures.fresh(two=true).use { f ->
            val r = f.repo; val (root,trash) = r.roots(); val model = LinkedHashMap<Bytes,Item>()
            val random = Random(seed); var sequence = 1L; var nextId = 1; var pinCounter = 1uL
            val other = f.core.currentSnapshot().header.zones[1]
            fun inTrash(id: Bytes): Boolean { var p=id; while(p != root && p != trash) p=model.getValue(p).parent; return p == trash }
            fun parent(): Bytes {
                val folders=model.filter { it.value.type == 1 && !inTrash(it.key) }.keys.toList()
                return if(folders.isEmpty() || random.nextBoolean()) root else folders[random.nextInt(folders.size)]
            }
            fun revise(item: Item,time: Long,content: Boolean) { item.revision++; if(content) item.content=time else item.metadata=time }
            repeat(128) { step ->
                val now=step.toLong()+100; var expected=WriteCode.Committed
                val normal=model.keys.filter { !inTrash(it) }; val files=normal.filter { model.getValue(it).type == 6 }
                val action=if(model.isEmpty()) 0 else random.nextInt(9)
                val token=when(action) {
                    0,1 -> {
                        val objectId=id(nextId++); val dest=parent(); val type=if(action == 0) 1 else 6; val name="n${step%5}"
                        model[objectId]=Item(type,dest,name,now,now,now,value=if(type == 6) "v$step" else "")
                        if(type == 1) r.issueCreateFolder(r.stamp(),dest,name,id=objectId,now=now)
                        else r.issueSaveCard(r.stamp(),objectId,dest,name,P4Fixtures.card("v$step"),now=now)
                    }
                    2 -> {
                        if(normal.isEmpty()) { expected=WriteCode.NoOp; r.issueDelete(r.stamp(),emptyList(),now=now) }
                        else {
                            val objectId=normal[random.nextInt(normal.size)]; val item=model.getValue(objectId); val h=r.handle(objectId)
                            item.name="r$step"; revise(item,now,item.type == 6)
                            r.issueMetadata(r.stamp(),h,name=item.name,now=now)
                        }
                    }
                    3 -> {
                        if(normal.isEmpty()) { expected=WriteCode.NoOp; r.issueMove(r.stamp(),emptyList(),root,now=now) }
                        else {
                            val objectId=normal[random.nextInt(normal.size)]; val item=model.getValue(objectId); val dest=parent(); val h=r.handle(objectId)
                            var p=dest; var cycle=p == objectId
                            while(p != root && p != trash) { p=model.getValue(p).parent; if(p == objectId) cycle=true }
                            if(cycle) expected=WriteCode.ValidationError else if(item.parent == dest) expected=WriteCode.NoOp
                            else { item.parent=dest; revise(item,now,false) }
                            r.issueMove(r.stamp(),listOf(h,h),dest,now=now)
                        }
                    }
                    4 -> {
                        val selected=normal.shuffled(kotlin.random.Random(seed+step)).take(3); val handles=selected.map { r.handle(it) }
                        val normalized=selected.filter { objectId ->
                            var p=model.getValue(objectId).parent; var keep=true
                            while(p != root && p != trash) { if(p in selected) keep=false; p=model.getValue(p).parent }; keep
                        }
                        if(normalized.isEmpty()) expected=WriteCode.NoOp
                        normalized.forEach { model.getValue(it).also { item -> item.parent=trash; revise(item,now,false) } }
                        r.issueDelete(r.stamp(),handles,now=now)
                    }
                    5 -> {
                        val roots=model.filterValues { it.parent == trash }.keys.toList(); val recovery=id(nextId++)
                        if(roots.isEmpty()) expected=WriteCode.NoOp else {
                            val names=model.values.filter { it.type == 1 && it.parent == root }.map { it.name }
                            var n=1; var name="Восстановленное"; while(name in names) { n++; name="Восстановленное $n" }
                            model[recovery]=Item(1,root,name,now,now,now)
                            roots.forEach { model.getValue(it).also { item -> item.parent=recovery; revise(item,now,false) } }
                        }
                        r.issueRestoreAll(r.stamp(),now=now,recoveryFolderId=recovery)
                    }
                    6 -> {
                        val removed=model.keys.filter { inTrash(it) }; if(removed.isEmpty()) expected=WriteCode.NoOp
                        removed.forEach { model.remove(it) }; r.issueClearTrash(r.stamp(),now=now)
                    }
                    7 -> {
                        if(files.isEmpty()) { expected=WriteCode.NoOp; r.issueMove(r.stamp(),emptyList(),root,now=now) }
                        else {
                            val objectId=files[random.nextInt(files.size)]; val item=model.getValue(objectId); val h=r.handle(objectId); val enabled=random.nextBoolean()
                            if(item.pinned == enabled) expected=WriteCode.NoOp else {
                                item.pinned=enabled; item.pin=if(enabled) pinCounter++ else null; revise(item,now,false)
                            }
                            r.issuePin(r.stamp(),h,enabled,now=now)
                        }
                    }
                    else -> {
                        if(files.isEmpty()) { expected=WriteCode.NoOp; r.issueMove(r.stamp(),emptyList(),root,now=now) }
                        else {
                            val objectId=files[random.nextInt(files.size)]; val item=model.getValue(objectId); val h=r.handle(objectId)
                            item.value="saved$step"; revise(item,now,true)
                            r.issueSaveCard(r.stamp(),objectId,item.parent,item.name,P4Fixtures.card(item.value),h.revision,now=now)
                        }
                    }
                }
                val outcome=r.submit(token)
                check(outcome.code == expected) { "Model code seed=$seed step=$step action=$action actual=${outcome.code}" }
                if(expected == WriteCode.Committed) sequence++
                check(r.stamp().sequence == sequence)
                val actual=r.allMetadata().associateBy { it.id }; check(actual.keys == model.keys)
                for((objectId,e) in model) {
                    val a=actual.getValue(objectId)
                    check(a.type == e.type && a.parent == e.parent && a.name == e.name && a.createdAt == e.created &&
                        a.contentModifiedAt == e.content && a.metadataModifiedAt == e.metadata && a.revision == e.revision && a.pinned == e.pinned && a.pinSequence == e.pin)
                    if(e.type == 6) check((r.readCard(objectId).body.fields.single().value as TextValue).text == e.value)
                }
                val view=f.core.readZone { _,_,view,_,_ -> view }; check(view.root!!.nextPinSequence == pinCounter)
                val otherNow=f.core.currentSnapshot().header.zones[1]
                check(DescriptorCodec.logicalBytes(other).contentEquals(DescriptorCodec.logicalBytes(otherNow)) && other.catalogue.hash == otherNow.catalogue.hash)
            }
            println("P4 independent model seed=$seed commands=128 objects=${model.size} PASS")
        }
    }
}
