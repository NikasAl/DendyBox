; Тестовая игра для проверки мультикарта: заливает экран одним цветом.
; Цвет задаётся GAME_COLOR; для второй игры сборщик подставит другой.
; Mapper 2 (UNROM), PRG 128КиБ (банки 0-6 — заполнитель $FF, банк 7 — код).

        .org $C000
GAME_COLOR = $2A        ; зелёный (тест 1)

PPUSTATUS = $2002
PPUCTRL   = $2000
PPUMASK   = $2001
PPUADDR   = $2006
PPUDATA   = $2007
PPUSCROLL = $2005

Reset:
        SEI
        CLD
        LDX #$FF
        TXS
        LDA #$77
        STA $0007       ; КАНАРЕЙКА 1: код игры получил управление
        LDA #0
        STA $E000       ; «разлочка» маппера 324: запись 0 (V = 0)

@v1:    BIT PPUSTATUS
        BPL @v1
@v2:    BIT PPUSTATUS
        BPL @v2
        LDA #$88
        STA $0008       ; КАНАРЕЙКА 2: дожились до конца ожидания vblank
        LDA #0
        STA PPUCTRL
        STA PPUMASK

        ; CHR-RAM $0000-$0FFF <- $FF (сплошные тайлы)
        LDA #$00
        STA PPUADDR
        STA PPUADDR
        LDA #$FF
        LDX #$10
@chrP:  LDY #0
@chrL:  STA PPUDATA
        INY
        BNE @chrL
        DEX
        BNE @chrP

        ; палитра: [0F, 0F, 0F, GAME_COLOR] x8 — цвет на позиции 3,
        ; т.к. сплошные тайлы $FF = оба плоскостных бита = позиция 3
        LDA #$3F
        STA PPUADDR
        LDA #$00
        STA PPUADDR
        LDX #8
@pal:   LDA #$0F
        STA PPUDATA
        STA PPUDATA
        STA PPUDATA
        LDA #GAME_COLOR
        STA PPUDATA
        DEX
        BNE @pal

        ; nametable $2000 <- тайл 1 (960) + атрибуты 01 (64)
        LDA #$20
        STA PPUADDR
        LDA #$00
        STA PPUADDR
        LDX #4
        LDA #1
@ntP:   LDY #0
@ntL:   STA PPUDATA
        INY
        BNE @ntL
        DEX
        BNE @ntP

        LDA #$20
        STA PPUADDR
        LDA #$00
        STA PPUADDR     ; вернуть t = $2000: после заливки v "доехал" до $2400,
                        ; а записи $2005 не сбрасывают биты выбора намтаблицы
        LDA #0
        STA PPUSCROLL
        STA PPUSCROLL
        LDA #$80
        STA PPUCTRL
        LDA #$08
        STA PPUMASK

@loop:  INC $0001       ; heartbeat для отладки
        LDA #0
        STA $E000       ; постоянная запись банка 0 — бит 7 защёлки = 0
        JMP @loop

NmiHandler:
        RTI
IrqHandler:
        RTI

        .org $FFFA
        .dw NmiHandler
        .dw Reset
        .dw IrqHandler
