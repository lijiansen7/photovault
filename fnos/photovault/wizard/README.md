# 卸载向导（可选）

fpk 支持在卸载时通过 wizard/uninstall 询问用户「保留 / 删除数据」，
并在 cmd/uninstall_callback 里根据 wizard_data_action 环境变量执行。

PhotoVault 的照片存放在应用数据目录（TRIM_PKGVAR/data）下，
默认卸载流程不会动它。如需提供向导，请参考官方文档
https://developer.fnnas.com/docs/ 里的用户向导章节补充。
