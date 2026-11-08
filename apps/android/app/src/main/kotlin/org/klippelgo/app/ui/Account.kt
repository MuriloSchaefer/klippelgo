package org.klippelgo.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.klippelgo.app.AppViewModel
import org.klippelgo.app.JoinState
import org.klippelgo.app.R
import org.klippelgo.app.UiState
import org.klippelgo.app.ui.tags.Tags
import org.klippelgo.chain.AccountKeys
import org.klippelgo.chain.GuardedAccount
import org.klippelgo.chain.KeyChange
import org.klippelgo.chain.KeyCodes
import org.klippelgo.chain.KeyKind
import org.web3j.utils.Numeric
import java.text.DateFormat
import java.util.Date

/**
 * Says so, above every screen, when the account needs the person: a guardian's recovery is waiting (it replaces
 * every key in two days unless a majority of the devices cancels it), a change of keys another device proposed
 * waits for this phone's approval, or this phone was taken off the account. Opens the account screen.
 */
@Composable
fun AccountNotice(state: UiState, onOpen: () -> Unit) {
    val keys = state.keys ?: return
    if (state.accountScreen) return
    val (status, text) = when {
        keys.deployed && !keys.thisDeviceIsKey -> Tags.ACCOUNT_NOTICE_REMOVED to stringResource(R.string.keys_notice_removed)
        keys.recovery != null -> Tags.ACCOUNT_NOTICE_RECOVERY to stringResource(R.string.keys_notice_recovery, date(keys.recovery!!.readyAt))
        keys.pending.any { !it.approvedByThisDevice } -> Tags.ACCOUNT_NOTICE_APPROVE to stringResource(R.string.keys_notice_approve)
        else -> return
    }
    val urgent = status != Tags.ACCOUNT_NOTICE_APPROVE
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp).fillMaxWidth().testTag(Tags.ACCOUNT_NOTICE),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).testTag(Tags.accountNoticeStatus(status)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.testTag(Tags.ACCOUNT_NOTICE_TEXT), fontSize = 13.sp, lineHeight = 18.sp)
            SecondaryButton(stringResource(R.string.keys_open), onOpen, Modifier.fillMaxWidth().testTag(Tags.ACCOUNT_NOTICE_OPEN))
        }
    }
}

/**
 * The account's devices, the changes to them waiting for a majority, the guardian, and the accounts this one guards.
 * Every change of keys is approved by a majority of them, so one phone taken from its owner cannot lock the others
 * out, and the others can take it off.
 */
@Composable
fun AccountScreen(state: UiState, vm: AppViewModel) {
    val keys = state.keys
    ScrollScreen(Tags.ACCOUNT) {
        BackButton({ vm.openAccount(false) }, Modifier.padding(start = 12.dp, top = 20.dp))
        Column(Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.keys_title))
            Muted(stringResource(R.string.keys_body))
            Text(state.address, Modifier.testTag(Tags.ACCOUNT_ADDRESS), fontFamily = PlexMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (keys == null) {
                Muted(stringResource(R.string.keys_loading))
                return@Column
            }
            if (keys.deployed && !keys.thisDeviceIsKey) Removed(vm)
            keys.recovery?.let { Recovery(state, keys, vm) }
            // What waits for this phone comes first.
            if (keys.pending.isNotEmpty()) Pending(state, keys, vm)
            Devices(state, keys, vm)
            ThisDevice(state, vm)
            OtherAccounts(state, vm)
            Guardian(state, keys, vm)
            if (state.guarded.isNotEmpty()) Guarded(state, vm)
        }
    }
}

@Composable
private fun Removed(vm: AppViewModel) {
    KCard(Modifier.testTag(Tags.ACCOUNT_REMOVED)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keys_removed_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Muted(stringResource(R.string.keys_removed_body), size = 13.sp)
            SecondaryButton(stringResource(R.string.keys_leave), vm::leaveJoined, Modifier.fillMaxWidth().testTag(Tags.ACCOUNT_LEAVE))
        }
    }
}

@Composable
private fun Recovery(state: UiState, keys: AccountKeys, vm: AppViewModel) {
    val r = keys.recovery ?: return
    val cancelling = keys.pending.firstOrNull { it.cancelsOnly }
    KCard(Modifier.testTag(Tags.ACCOUNT_RECOVERY)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keys_recovery_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            Muted(stringResource(R.string.keys_recovery_body, r.fingerprint, date(r.readyAt)), size = 13.sp)
            // A cancel another device already proposed is approved, not proposed again.
            if (cancelling == null) {
                PrimaryButton(stringResource(R.string.keys_recovery_cancel), vm::cancelRecovery, Modifier.testTag(Tags.ACCOUNT_RECOVERY_CANCEL), busy = state.busy)
            } else if (!cancelling.approvedByThisDevice) {
                PrimaryButton(stringResource(R.string.keys_recovery_cancel), { vm.approveChange(cancelling) }, Modifier.testTag(Tags.ACCOUNT_RECOVERY_CANCEL), busy = state.busy)
            }
        }
    }
}

@Composable
private fun Devices(state: UiState, keys: AccountKeys, vm: AppViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val signer = KeyCodes.signerOf(code, state.deviceKeyVerifier)
    KCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.keys_devices), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.keys_majority, keys.majority, keys.keys.size), Modifier.testTag(Tags.ACCOUNT_MAJORITY), size = 13.sp)
            if (keys.keys.count { it.kind == KeyKind.Device } < RECOMMENDED_DEVICES) {
                Muted(stringResource(R.string.keys_recommend), Modifier.testTag(Tags.ACCOUNT_RECOMMEND), size = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            for (key in keys.keys) {
                Row(Modifier.fillMaxWidth().testTag(Tags.accountKey(key.fingerprint)), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // The name this phone knows the key by, if any: its own, or what the code that added it said.
                        nameOf(state, key.signer, key.thisDevice)?.let {
                            Text(it, Modifier.testTag(Tags.accountKeyName(key.fingerprint)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(key.fingerprint, fontFamily = PlexMono, fontSize = 13.sp)
                        Muted(
                            stringResource(if (key.kind == KeyKind.Device) R.string.keys_kind_device else R.string.keys_kind_other) +
                                if (key.thisDevice) " · " + stringResource(R.string.keys_this_device) else "",
                            if (key.thisDevice) Modifier.testTag(Tags.accountKeyThis(key.fingerprint)) else Modifier,
                            size = 12.sp,
                        )
                    }
                    // The last key cannot go; this phone's own goes from another device.
                    if (!key.thisDevice && keys.keys.size > 1 && keys.thisDeviceIsKey) {
                        TextButton({ vm.removeDevice(key.signer) }, Modifier.testTag(Tags.accountKeyRemove(key.fingerprint)), enabled = !state.busy) {
                            Text(stringResource(R.string.keys_remove))
                        }
                    }
                }
            }
            if (keys.thisDeviceIsKey) {
                FieldLabel(stringResource(R.string.keys_add))
                ScanOrPaste(Tags.ACCOUNT_ADD_SCAN, Tags.ACCOUNT_ADD_PASTE) { code = it }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    placeholder = { Muted(stringResource(R.string.keys_add_hint), size = 13.sp) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.ACCOUNT_ADD_CODE),
                )
                // Something pasted that is no code: most likely the other phone's fingerprint, which cannot be added.
                if (code.isNotBlank() && signer == null) {
                    val fingerprintPasted = code.filterNot { it.isWhitespace() }.matches(Regex("[0-9a-fA-F]{12,16}"))
                    Muted(
                        stringResource(if (fingerprintPasted) R.string.keys_is_fingerprint else R.string.keys_bad_code),
                        Modifier.testTag(Tags.ACCOUNT_ADD_INVALID),
                        size = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // Which device the code says it is, and what that device shows under its code: the same digits, or it
                // is not that device's key. The name is only what the code says; the fingerprint is what to check.
                if (signer != null) KeyCodes.nameOf(code)?.let {
                    Text(stringResource(R.string.keys_add_name, it), Modifier.testTag(Tags.ACCOUNT_ADD_NAME), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                signer?.let { Muted(stringResource(R.string.keys_check_fingerprint, KeyCodes.fingerprint(it)), Modifier.testTag(Tags.ACCOUNT_ADD_FINGERPRINT), size = 13.sp) }
                PrimaryButton(
                    stringResource(R.string.keys_add_submit),
                    { confirming = true },
                    Modifier.testTag(Tags.ACCOUNT_ADD),
                    enabled = signer != null,
                    busy = state.busy,
                )
                // Asked once more, with what the device is: a key added signs and spends for the account.
                if (confirming && signer != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text(stringResource(R.string.keys_add_confirm_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                KeyCodes.nameOf(code)?.let { Text(it, Modifier.testTag(Tags.ACCOUNT_ADD_CONFIRM_NAME), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                                Text(KeyCodes.fingerprint(signer), Modifier.testTag(Tags.ACCOUNT_ADD_CONFIRM_FINGERPRINT), fontFamily = PlexMono, fontSize = 15.sp)
                                Muted(stringResource(R.string.keys_add_confirm_body), size = 13.sp)
                            }
                        },
                        confirmButton = {
                            TextButton(
                                {
                                    confirming = false
                                    vm.addDevice(code)
                                    code = ""
                                },
                                Modifier.testTag(Tags.ACCOUNT_ADD_CONFIRM_YES),
                            ) { Text(stringResource(R.string.keys_add_submit)) }
                        },
                        dismissButton = {
                            TextButton({ confirming = false }, Modifier.testTag(Tags.ACCOUNT_ADD_CONFIRM_NO)) { Text(stringResource(R.string.keys_cancel)) }
                        },
                        // A dialog is a window of its own, so it says again that its tags may be read.
                        modifier = Modifier.testable().testTag(Tags.ACCOUNT_ADD_CONFIRM),
                    )
                }
            }
        }
    }
}

@Composable
private fun Pending(state: UiState, keys: AccountKeys, vm: AppViewModel) {
    KCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keys_pending), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            for (change in keys.pending) ChangeCard(state, keys, change, vm)
        }
    }
}

@Composable
private fun ChangeCard(state: UiState, keys: AccountKeys, change: KeyChange, vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().testTag(Tags.accountChange(change.id)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A key by its fingerprint, which is what identifies it. The name this phone knows goes on a line of its own
        // below, so that no name can stand in the fingerprint's place.
        fun fp(s: String) = KeyCodes.fingerprint(Numeric.hexStringToByteArray(s))
        @Composable
        fun named(s: String) {
            val mine = keys.keys.firstOrNull { it.signer.equals(s, ignoreCase = true) }?.thisDevice == true
            nameOf(state, s, mine)?.let { Muted(it, size = 12.sp) }
        }
        for (a in change.add) {
            Text(stringResource(R.string.keys_change_add, fp(a)), Modifier.testTag(Tags.accountChangeLine(change.id, "add")), fontSize = 14.sp)
            named(a)
        }
        for (r in change.remove) {
            val mine = keys.keys.firstOrNull { it.signer.equals(r, ignoreCase = true) }?.thisDevice == true
            Text(
                stringResource(if (mine) R.string.keys_change_remove_this else R.string.keys_change_remove, fp(r)),
                Modifier.testTag(Tags.accountChangeLine(change.id, "remove")),
                fontSize = 14.sp,
                color = if (mine) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            named(r)
        }
        if (change.setsGuardian) {
            Text(
                change.guardian?.let { stringResource(R.string.keys_change_guardian, it) } ?: stringResource(R.string.keys_change_no_guardian),
                Modifier.testTag(Tags.accountChangeLine(change.id, "guardian")),
                fontSize = 14.sp,
            )
        }
        if (change.cancelsOnly) Text(stringResource(R.string.keys_change_cancel), Modifier.testTag(Tags.accountChangeLine(change.id, "cancel")), fontSize = 14.sp)
        Muted(stringResource(R.string.keys_change_approvals, change.approvals, keys.majority), Modifier.testTag(Tags.accountChangeApprovals(change.id)), size = 12.sp)
        if (!change.approvedByThisDevice && keys.thisDeviceIsKey) {
            SecondaryButton(stringResource(R.string.keys_approve), { vm.approveChange(change) }, Modifier.fillMaxWidth().testTag(Tags.accountChangeApprove(change.id)), enabled = !state.busy)
        }
    }
}

@Composable
private fun Guardian(state: UiState, keys: AccountKeys, vm: AppViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    KCard(Modifier.testTag(Tags.ACCOUNT_GUARDIAN)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.keys_guardian), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.keys_guardian_body), size = 13.sp)
            KeyValue(stringResource(R.string.keys_guardian_now), keys.guardian?.let(::shortAddress) ?: stringResource(R.string.keys_guardian_none), divider = false, tag = Tags.ACCOUNT_GUARDIAN_NOW)
            if (keys.thisDeviceIsKey) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Muted(stringResource(R.string.keys_guardian_hint), size = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.ACCOUNT_GUARDIAN_INPUT),
                )
                PrimaryButton(
                    stringResource(R.string.keys_guardian_set),
                    {
                        vm.setGuardian(address)
                        address = ""
                    },
                    Modifier.testTag(Tags.ACCOUNT_GUARDIAN_SET),
                    enabled = address.isNotBlank(),
                    busy = state.busy,
                )
                if (keys.guardian != null) {
                    TextButton({ vm.setGuardian("") }, Modifier.align(Alignment.CenterHorizontally).testTag(Tags.ACCOUNT_GUARDIAN_CLEAR), enabled = !state.busy) {
                        Text(stringResource(R.string.keys_guardian_clear))
                    }
                }
            }
        }
    }
}

/** The accounts that named this one their guardian: for each, the recovery to a new phone the person asks for. */
@Composable
private fun Guarded(state: UiState, vm: AppViewModel) {
    KCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.keys_guarded), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.keys_guarded_body), size = 13.sp)
            for (g in state.guarded) GuardedCard(state, g, vm)
        }
    }
}

@Composable
private fun GuardedCard(state: UiState, g: GuardedAccount, vm: AppViewModel) {
    var code by rememberSaveable(g.account) { mutableStateOf("") }
    val signer = KeyCodes.signerOf(code, state.deviceKeyVerifier)
    Column(Modifier.fillMaxWidth().testTag(Tags.guarded(g.account)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(g.account, fontFamily = PlexMono, fontSize = 12.sp)
        val r = g.recovery
        if (r != null) {
            // The chain's clock decides, not the phone's: a phone set ahead would offer a finish the account refuses.
            val ready = g.ready
            Muted(
                stringResource(if (ready) R.string.keys_guarded_ready else R.string.keys_guarded_waiting, r.fingerprint, date(r.readyAt)),
                Modifier.testTag(Tags.guardedRecovery(g.account, if (ready) "ready" else "waiting")),
                size = 13.sp,
            )
            if (ready) PrimaryButton(stringResource(R.string.keys_guarded_finish), { vm.finishRecovery(g.account) }, Modifier.testTag(Tags.guardedFinish(g.account)), busy = state.busy)
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            placeholder = { Muted(stringResource(R.string.keys_guarded_hint), size = 13.sp) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag(Tags.guardedCode(g.account)),
        )
        signer?.let { Muted(stringResource(R.string.keys_check_fingerprint, KeyCodes.fingerprint(it)), Modifier.testTag(Tags.guardedFingerprint(g.account)), size = 13.sp) }
        ScanOrPaste(Tags.guardedScan(g.account), Tags.guardedPaste(g.account)) { code = it }
        SecondaryButton(
            stringResource(R.string.keys_guarded_start),
            {
                vm.startRecovery(g.account, code)
                code = ""
            },
            Modifier.fillMaxWidth().testTag(Tags.guardedStart(g.account)),
            enabled = signer != null && !state.busy,
        )
    }
}

/**
 * A new phone joining an account the person has: it shows its code to a device already on the account, or to the
 * guardian if every device is lost, and waits for the account to have its key. The person confirms the account by
 * its address, which their other device or their guardian shows too.
 */
@Composable
fun JoinScreen(join: JoinState?, onBack: () -> Unit, onUse: (String) -> Unit) {
    ScrollScreen(Tags.JOIN) {
        BackButton(onBack, Modifier.padding(start = 12.dp, top = 20.dp))
        Column(Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Title(stringResource(R.string.join_title))
            Muted(stringResource(R.string.join_body))
        }
        if (join == null) return@ScrollScreen
        KCard(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldLabel(stringResource(R.string.join_code))
                KeyCodeBox(join.code, join.fingerprint, Tags.JOIN_CODE, Tags.JOIN_FINGERPRINT, Tags.JOIN_COPY, Tags.JOIN_SHARE, Tags.JOIN_QR)
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((account, readyAt) in join.recoveries) {
                Muted(stringResource(R.string.join_recovery, shortAddress(account), date(readyAt)), Modifier.testTag(Tags.joinRecovery(account)), size = 13.sp)
            }
            if (join.found.isEmpty()) {
                Muted(stringResource(R.string.join_waiting), Modifier.testTag(Tags.JOIN_WAITING), size = 13.sp)
            }
            for (account in join.found) {
                KCard(Modifier.testTag(Tags.joinFound(account))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.join_found), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(account, fontFamily = PlexMono, fontSize = 12.sp)
                        Muted(stringResource(R.string.join_found_check), size = 13.sp)
                        PrimaryButton(stringResource(R.string.join_use), { onUse(account) }, Modifier.testTag(Tags.joinUse(account)))
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/**
 * A key code as a person passes it on: the code itself, to select, with icons that copy it and share it, and under
 * it the fingerprint the other screen shows when the code is pasted there.
 */
@Composable
private fun KeyCodeBox(
    code: String,
    fingerprint: String,
    codeTag: String,
    fingerprintTag: String,
    copyTag: String,
    shareTag: String,
    qrTag: String,
    onShown: () -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val qr = remember(code) { qrImage(code) }
    // Shown, so an account may add this phone soon: it will ask the person whether to join.
    androidx.compose.runtime.LaunchedEffect(code) { onShown() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The code as a QR code, for the other phone's camera: the same text as below, name included.
        Image(
            qr,
            contentDescription = stringResource(R.string.keys_qr),
            modifier = Modifier.align(Alignment.CenterHorizontally).size(220.dp).background(androidx.compose.ui.graphics.Color.White).padding(8.dp).testTag(qrTag),
        )
        KeyCodes.nameOf(code)?.let { Text(it, Modifier.align(Alignment.CenterHorizontally), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        SelectionContainer { Text(code, Modifier.testTag(codeTag), fontFamily = PlexMono, fontSize = 12.sp) }
        // Buttons of their own under the code: the code is one long word, and would push anything beside it off.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconLabelButton(
                KIcons.Copy,
                stringResource(R.string.join_copy),
                {
                    clipboard.setText(AnnotatedString(code))
                    onShown()
                },
                Modifier.weight(1f).testTag(copyTag),
            )
            IconLabelButton(
                KIcons.Share,
                stringResource(R.string.join_share),
                {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
                    context.startActivity(Intent.createChooser(send, null))
                    onShown()
                },
                Modifier.weight(1f).testTag(shareTag),
            )
        }
        Text(stringResource(R.string.join_fingerprint, fingerprint), Modifier.testTag(fingerprintTag), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** This phone's code, for a device on another account of the person's to add it, and the name it goes by. */
@Composable
private fun ThisDevice(state: UiState, vm: AppViewModel) {
    if (state.keyCode.isBlank()) return
    var name by rememberSaveable(state.deviceName) { mutableStateOf(state.deviceName) }
    // Shown on request only: showing it is what lets an account that adds this phone soon after ask to be joined,
    // so opening this screen for anything else does not.
    var shown by rememberSaveable { mutableStateOf(false) }
    KCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.keys_this_code_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.keys_this_code_body), size = 13.sp)
            if (shown) {
                KeyCodeBox(
                    state.keyCode, state.keyFingerprint, Tags.ACCOUNT_THIS_CODE, Tags.ACCOUNT_THIS_FINGERPRINT, Tags.ACCOUNT_THIS_COPY,
                    Tags.ACCOUNT_THIS_SHARE, Tags.ACCOUNT_THIS_QR, onShown = vm::codeShown,
                )
            } else {
                IconLabelButton(KIcons.Phone, stringResource(R.string.keys_this_show), { shown = true }, Modifier.fillMaxWidth().testTag(Tags.ACCOUNT_THIS_SHOW))
            }
            FieldLabel(stringResource(R.string.keys_this_name))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(KeyCodes.MAX_NAME) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).testTag(Tags.ACCOUNT_THIS_NAME),
                )
                TextButton({ vm.setDeviceName(name) }, Modifier.testTag(Tags.ACCOUNT_THIS_NAME_SAVE), enabled = name != state.deviceName) {
                    Text(stringResource(R.string.keys_this_name_save))
                }
            }
            Muted(stringResource(R.string.keys_this_name_body), size = 12.sp)
        }
    }
}

/** The name this phone knows a key by: its own name for its own key, else what the code that added it carried. */
private fun nameOf(state: UiState, signer: String, thisDevice: Boolean): String? =
    if (thisDevice) state.deviceName.takeIf { it.isNotBlank() } else state.keyNames[signer.lowercase()]

/**
 * Two ways to take another device's code: the camera reads its QR code, or one tap pastes what was copied (between
 * emulators, whose cameras cannot see each other, the computer's clipboard carries it). Either only fills the field:
 * nothing is added until the person checks the fingerprint and taps Add.
 */
@Composable
private fun ScanOrPaste(scanTag: String, pasteTag: String, onCode: (String) -> Unit) {
    val prompt = stringResource(R.string.keys_scan_prompt)
    val clipboard = LocalClipboardManager.current
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onCode) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconLabelButton(
            KIcons.Camera,
            stringResource(R.string.keys_scan),
            {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            Modifier.weight(1f).testTag(scanTag),
        )
        IconLabelButton(KIcons.Copy, stringResource(R.string.keys_paste), { clipboard.getText()?.text?.let(onCode) }, Modifier.weight(1f).testTag(pasteTag))
    }
}

/** An outlined button with an icon before its label. */
@Composable
private fun IconLabelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** A QR code of `text`, black on white, one module of margin: ZXing draws it, on this phone. */
private fun qrImage(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val scale = 8
    val bitmap = Bitmap.createBitmap(matrix.width * scale, matrix.height * scale, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        val colour = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        for (dy in 0 until scale) for (dx in 0 until scale) bitmap.setPixel(x * scale + dx, y * scale + dy, colour)
    }
    return bitmap.asImageBitmap()
}

/** Other accounts this phone's key was added to: the person picks one by its address, which the other device shows. */
@Composable
private fun OtherAccounts(state: UiState, vm: AppViewModel) {
    if (state.otherAccounts.isEmpty()) return
    KCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.keys_other_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(stringResource(R.string.keys_other_body), size = 13.sp)
            for (account in state.otherAccounts) {
                Column(Modifier.fillMaxWidth().testTag(Tags.accountOther(account)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account, fontFamily = PlexMono, fontSize = 12.sp)
                    SecondaryButton(stringResource(R.string.join_use), { vm.switchAccount(account) }, Modifier.fillMaxWidth().testTag(Tags.accountOtherUse(account)), enabled = !state.busy)
                }
            }
        }
    }
}

/**
 * An account added this phone's key, soon after its code went out: the person checks that its devices are theirs
 * (the fingerprints the other phone shows) and joins it, or not now. Over every screen.
 */
@Composable
fun JoinOfferDialog(state: UiState, vm: AppViewModel) {
    val offer = state.joinOffer ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = vm::declineJoinOffer,
        title = { Text(stringResource(R.string.join_offer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(stringResource(R.string.join_offer_body), size = 13.sp)
                Text(offer.account, Modifier.testTag(Tags.JOIN_OFFER_ACCOUNT), fontFamily = PlexMono, fontSize = 12.sp)
                FieldLabel(stringResource(R.string.join_offer_devices))
                for (k in offer.keys) {
                    val fp = KeyCodes.fingerprint(Numeric.hexStringToByteArray(k))
                    Column(Modifier.testTag(Tags.joinOfferKey(fp))) {
                        Text(fp, fontFamily = PlexMono, fontSize = 14.sp)
                        if (fp.replace(" ", "") == state.keyFingerprint.replace(" ", "")) Muted(stringResource(R.string.keys_this_device), size = 12.sp)
                        else state.keyNames[k.lowercase()]?.let { Muted(it, size = 12.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(vm::acceptJoinOffer, Modifier.testTag(Tags.JOIN_OFFER_ACCEPT)) { Text(stringResource(R.string.join_offer_accept)) }
        },
        dismissButton = {
            TextButton(vm::declineJoinOffer, Modifier.testTag(Tags.JOIN_OFFER_DECLINE)) { Text(stringResource(R.string.join_offer_decline)) }
        },
        modifier = Modifier.testable().testTag(Tags.JOIN_OFFER),
    )
}

/** What the app recommends: with three, one taken phone is taken off by the other two. */
private const val RECOMMENDED_DEVICES = 3

/** A moment on chain (Unix seconds), as the phone's language writes a date and time. */
private fun date(seconds: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))
