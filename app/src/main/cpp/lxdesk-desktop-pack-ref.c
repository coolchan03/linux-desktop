/* Keeps the embedded desktop pack referenced so the linker cannot drop it. */
extern const char lxdesk_desktop_pack[];

__attribute__((visibility("default"))) const char *lxdesk_desktop_pack_data(void) {
    return lxdesk_desktop_pack;
}
