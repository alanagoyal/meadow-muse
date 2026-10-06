from pathlib import Path

root = Path('patched')
p = root / 'smali/ai/muse/gadget/ble/GattPeripheral.smali'
s = p.read_text()
old = 'invoke-virtual {v9, v10}, Landroid/bluetooth/le/AdvertiseData$Builder;->setIncludeDeviceName(Z)Landroid/bluetooth/le/AdvertiseData$Builder;'
assert s.count(old) == 1
s = s.replace(old, old.replace('v9, v10', 'v9, v2'))
old = '    invoke-virtual {v9, v8, v5, v11}, Landroid/bluetooth/le/BluetoothLeAdvertiser;->startAdvertising(Landroid/bluetooth/le/AdvertiseSettings;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/AdvertiseCallback;)V'
assert s.count(old) == 1
s = s.replace(old, '''    new-instance v6, Landroid/bluetooth/le/AdvertiseData$Builder;
    invoke-direct {v6}, Landroid/bluetooth/le/AdvertiseData$Builder;-><init>()V
    invoke-virtual {v6, v10}, Landroid/bluetooth/le/AdvertiseData$Builder;->setIncludeDeviceName(Z)Landroid/bluetooth/le/AdvertiseData$Builder;
    invoke-virtual {v6}, Landroid/bluetooth/le/AdvertiseData$Builder;->build()Landroid/bluetooth/le/AdvertiseData;
    move-result-object v6
    invoke-virtual {v9, v8, v5, v6, v11}, Landroid/bluetooth/le/BluetoothLeAdvertiser;->startAdvertising(Landroid/bluetooth/le/AdvertiseSettings;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/AdvertiseData;Landroid/bluetooth/le/AdvertiseCallback;)V''')
p.write_text(s)

p = root / 'smali/ai/muse/gadget/ui/MainActivity.smali'
s = p.read_text()
start = s.index('.method protected onCreate(')
end = s.index('.end method', start)
method = s[start:end]
method = method.replace('    .line 109\n', '''    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;
    move-result-object v0
    const/16 v1, 0x80
    invoke-virtual {v0, v1}, Landroid/view/Window;->addFlags(I)V
    invoke-direct {p0}, Lai/muse/gadget/ui/MainActivity;->startServiceIfPermitted()V
    return-void
.end method

.method private startServiceIfPermitted()V
    .locals 3
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I
    const/16 v1, 0x1f
    if-lt v0, v1, :permissions_ready
    const-string v0, "android.permission.BLUETOOTH_CONNECT"
    invoke-virtual {p0, v0}, Landroid/content/Context;->checkSelfPermission(Ljava/lang/String;)I
    move-result v0
    if-nez v0, :permissions_missing
    const-string v0, "android.permission.BLUETOOTH_ADVERTISE"
    invoke-virtual {p0, v0}, Landroid/content/Context;->checkSelfPermission(Ljava/lang/String;)I
    move-result v0
    if-nez v0, :permissions_missing
    :permissions_ready
''')
method = method.replace('    .line 112\n    return-void', '    :permissions_missing\n    return-void')
s = s[:start] + method + s[end:]
assert '.method public onRequestPermissionsResult(' not in s
s += '''
.method public onRequestPermissionsResult(I[Ljava/lang/String;[I)V
    .locals 1
    invoke-super {p0, p1, p2, p3}, Landroid/app/Activity;->onRequestPermissionsResult(I[Ljava/lang/String;[I)V
    const/4 v0, 0x1
    if-ne p1, v0, :done_permissions
    iget-boolean v0, p0, Lai/muse/gadget/ui/MainActivity;->bound:Z
    if-nez v0, :done_permissions
    invoke-direct {p0}, Lai/muse/gadget/ui/MainActivity;->startServiceIfPermitted()V
    :done_permissions
    return-void
.end method
'''
p.write_text(s)

# A separate application ID preserves the original installation and its data.
p = root / 'AndroidManifest.xml'
s = p.read_text().replace('package="ai.muse.gadget"', 'package="ai.muse.gadget.fixed"')
s = s.replace('android:name=".ui.MainActivity"', 'android:name="ai.muse.gadget.ui.MainActivity"')
s = s.replace('android:name=".service.GadgetService"', 'android:name="ai.muse.gadget.service.GadgetService"')
s = s.replace('android:label="Muse Gadget"', 'android:label="Muse Gadget Fixed"')
p.write_text(s)
