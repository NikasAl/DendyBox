; ============================================================================
; DendyBox Multicart Menu
; ----------------------------------------------------------------------------
; Меню выбора игры для мультикарта на базе NES 2.0 Mapper 324
; (FARID_UNROM_8-IN-1). Собирается в образ окна 0 (банки 0 и 7):
;
;   банк 0 ($8000-$BFFF) — данные: шрифт, текст экрана, палитра
;   банк 7 ($C000-$FFFF) — код меню + векторы; меню живёт в фиксированном
;                          банке своего окна, игры живут в окнах 1..7
;
; Схема запуска игры (mapper 324, см. fceumm src/boards/faridunrom.c):
;   1) запись значения 0 в $8000-$FFFF  — «разлочка» (сброс бита 7 защёлки);
;   2) запись $80|(окно<<4) в адрес, где в ROM лежит $FF (bus-conflict-safe);
;   3) JMP ($FFFC) — вектор сброса игры в её фиксированном банке.
; Кнопка RESET сбрасывает внешние биты защёлки — возврат в окно 0 (меню).
;
; Сборка: asm6f menu.asm multicart_menu.bin
; Затем make_multicart.py пропатчит в готовом bin: число игр ($C800),
; строки меню ($8800) и палитру ($8FC0).
; ============================================================================

        .org $8000

        ; ---- регистры PPU/APU ----
PPUSTATUS = $2002
PPUCTRL   = $2000
PPUMASK   = $2001
OAMADDR   = $2003
PPUSCROLL = $2005
PPUADDR   = $2006
PPUDATA   = $2007
PAD1      = $4016
PAD2      = $4017

        ; ---- zero page ----
frameFlag = $00        ; NMI ставит 1, главный цикл сбрасывает
sel       = $01        ; выбранный пункт (0..count-1)
prevPad   = $02
joy       = $03
tmp       = $04
ptrLo     = $05
ptrHi     = $06

        ; ---- данные окна 0 (фиксированные адреса, патчит сборщик) ----
FONT      = $8000      ; fontTiles .. fontTilesEnd (см. font.inc)
SCREEN    = $8800      ; 960 байт nametable ($2000)
PALETTE   = $8FC0      ; 32 байта (16 BG + 16 sprite)
MENUDATA  = $C800      ; +0: число игр; +4: launchPad ($FF x16); +20: окна (7)
LAUNCHPAD = MENUDATA+4
WINDOWVAL = MENUDATA+20

CURSOR    = $1E        ; тайл '>'
BLANK     = $00

; ------------------------- банк 0: данные -----------------------------------
        .org $8000
        .include "font.inc"          ; 132 тайла = 1056 байт

        .org $8800
screenData:  dsb 960                 ; текст экрана — пропатчит make_multicart.py

        .org $8FC0
paletteData: dsb 32

; ------------------------- банк 7: код --------------------------------------
        .org $C000
Reset:
        SEI
        CLD
        LDX #$FF
        TXS

        ; --- разлочить маппер и гарантировать банк 0 в $8000 ---
        ; запись значения 0: V = 0 AND ROM = 0 -> бит7=0, inner=0,
        ; внешние биты (уже сброшенные RESET'ом) не трогаются
        LDA #0
        STA LAUNCHPAD

        ; --- дождаться двух vblank ---
        BIT PPUSTATUS
@v1:    BIT PPUSTATUS
        BPL @v1
@v2:    BIT PPUSTATUS
        BPL @v2

        ; --- выключить рендер и NMI ---
        LDA #0
        STA PPUCTRL
        STA PPUMASK

        ; --- очистить RAM (кроме стека $0200 не трогаем? чистим всё) ---
        LDX #0
        LDA #0
@clr:   STA $0000,X
        STA $0100,X
        STA $0200,X
        STA $0300,X
        STA $0400,X
        STA $0500,X
        STA $0600,X
        STA $0700,X
        INX
        BNE @clr

        ; --- загрузить шрифт в CHR-RAM ($0000, pattern table 0) ---
        LDA #<FONT
        STA ptrLo
        LDA #>FONT
        STA ptrHi
        LDA #$00
        STA PPUADDR
        STA PPUADDR
        LDY #0
        LDX #4                    ; 4 полных страницы (1024 байта)
@chrP:  LDA (ptrLo),Y
        STA PPUDATA
        INY
        BNE @chrP
        INC ptrHi
        DEX
        BNE @chrP
        LDX #32                   ; ещё 32 байта (итого 1056)
@chrR:  LDA (ptrLo),Y
        STA PPUDATA
        INY
        BNE @chrR

        ; --- залить nametable из SCREEN ($2000) ---
        LDA #$20
        STA PPUADDR
        LDA #$00
        STA PPUADDR
        LDA #>SCREEN
        STA ptrHi
        LDA #<SCREEN
        STA ptrLo
        LDY #0
        LDX #3                    ; 768 байт
@ntP:   LDA (ptrLo),Y
        STA PPUDATA
        INY
        BNE @ntP
        INC ptrHi
        DEX
        BNE @ntP
        LDX #192                  ; ещё 192 (итого 960)
@ntR:   LDA (ptrLo),Y
        STA PPUDATA
        INY
        BNE @ntR
        LDX #64                   ; атрибуты = 0 (64 байта)
        LDA #0
@attr:  STA PPUDATA
        DEX
        BNE @attr

        ; --- палитра ---
        LDA #$3F
        STA PPUADDR
        LDA #$00
        STA PPUADDR
        LDX #0
@pal:   LDA PALETTE,X
        STA PPUDATA
        INX
        CPX #32
        BNE @pal

        ; --- переменные, скролл, запуск рендера ---
        LDA #0
        STA sel
        STA prevPad
        STA frameFlag
        STA PPUSCROLL
        STA PPUSCROLL
        LDA #$08                  ; показать фон
        STA PPUMASK
        LDA #$80                  ; NMI on
        STA PPUCTRL

        JSR UpdateCursor          ; нарисовать курсор в первой строке
        JSR ResetScroll

; ------------------------- главный цикл -------------------------------------
Main:
        LDA frameFlag
        BEQ Main
        LDA #0
        STA frameFlag

        JSR ReadPad
        ; pressed = joy & ~prevPad
        LDA prevPad
        EOR #$FF
        AND joy
        STA tmp
        LDA joy
        STA prevPad

        ; ---- ВВЕРХ ----
        LDA tmp
        AND #$08
        BEQ @down
        DEC sel
        BPL @upOk
        LDA MENUDATA              ; count
        SEC
        SBC #1
        STA sel
@upOk:  JSR UpdateCursor
        JSR ResetScroll
        JMP Main

        ; ---- ВНИЗ ----
@down:  LDA tmp
        AND #$04
        BEQ @start
        INC sel
        LDA sel
        CMP MENUDATA              ; >= count ?
        BCC @downOk
        LDA #0
        STA sel
@downOk:
        JSR UpdateCursor
        JSR ResetScroll
        JMP Main

        ; ---- СТАРТ / A — запуск ----
@start: LDA tmp
        AND #$10                  ; Start
        BNE Launch
        LDA tmp
        AND #$80                  ; A
        BEQ Main
        ; fallthrough -> Launch

; ------------------------- запуск игры --------------------------------------
; ВАЖНО: после записи выбора окна код меню в $C000 исчезает из шины (окно
; сменилось) — поэтому последовательность «запись в маппер + JMP ($FFFC)»
; исполняется из ТРАМПЛИНА В RAM ($0000): A9 V 8D 04 C8 6C FC FF
;   LDA #V ; STA LAUNCHPAD ; JMP ($FFFC)
Launch:
        LDA #0
        STA PPUCTRL               ; NMI off
        STA PPUMASK               ; рендер off
        ; 1) разлочка (бит7 <- 0): окно не меняется, меню продолжает работать
        LDA #0
        STA LAUNCHPAD
        ; 2) собрать трамплин в RAM: A9 V 8D 04 C8 6C FC FF
        LDA #$A9
        STA $0000                 ; LDA #V
        LDX sel
        LDA WINDOWVAL,X           ; V = $80 | (окно<<4)
        STA $0001
        LDA #$8D
        STA $0002                 ; STA $C804
        LDA #<LAUNCHPAD
        STA $0003
        LDA #>LAUNCHPAD
        STA $0004
        LDA #$6C
        STA $0005                 ; JMP ($FFFC)
        LDA #$FC
        STA $0006
        LDA #$FF
        STA $0007
        ; 3) исполнить из RAM: запись окна + прыжок на вектор сброса игры
        JMP $0000

; ------------------------- подпрограммы -------------------------------------
; Считывает джойстик 1 в joy (A,B,Select,Start,Up,Down,Left,Right = биты 7..0)
ReadPad:
        LDA #1
        STA PAD1
        LDA #0
        STA PAD1                  ; строб
        LDX #8
@rp:    LDA PAD1
        LSR A
        ROL joy
        DEX
        BNE @rp
        RTS

; Сброс скролла: записи $2006/$2007 выше меняют t — без сброса экран «уедет»
ResetScroll:
        LDA #0
        STA PPUSCROLL
        STA PPUSCROLL
        RTS

; Перерисовывает курсор: у всех строк столбец 1 = BLANK, у выбранной = CURSOR
UpdateCursor:
        LDX #0
@uc:    TXA
        CLC
        ADC #5                    ; строка 5 + X
        ASL A
        ASL A
        ASL A
        ASL A
        ASL A                     ; * 32
        STA tmp
        LDA #0
        ROL A                     ; старший бит row*32
        ADC #$20                  ; $2000 + ...
        STA PPUADDR
        LDA tmp
        CLC
        ADC #1                    ; столбец 1
        STA PPUADDR
        CPX sel
        LDA #BLANK
        BNE @tile
        LDA #CURSOR
@tile:  STA PPUDATA
        INX
        CPX MENUDATA              ; по числу игр
        BNE @uc
        RTS

; ------------------------- данные меню (банк 7) ------------------------------
        .org $C800
menuCount:  dsb 1                   ; число игр — патчит make_multicart.py
            dsb 3
launchPad:  .db $FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF,$FF
                                    ; цели для записей в маппер: в ROM тут $FF,
                                    ; поэтому V = записанное значение (bus conflict
                                    ; безопасно: V &= CartBR(A))
windowVal:  .db $90,$A0,$B0,$C0,$D0,$E0,$F0
                                    ; V = $80 | (окно<<4) для игр 1..7

; ------------------------- NMI/IRQ ------------------------------------------
NmiHandler:
        LDA #1
        STA frameFlag
        RTI

IrqHandler:
        RTI

        .org $FFFA
        .dw NmiHandler
        .dw Reset
        .dw IrqHandler
