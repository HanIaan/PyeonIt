# B8 이미지 테스트 자료

Pillow 13.0.0으로 만든 3×2 RGB 이미지다. 테스트 실행 시 Python이나 Pillow 의존성은 없다.
`clean.*`는 메타데이터 없는 업로드 회귀 테스트용이다.
`metadata.jpg`에는 실제 TIFF GPS IFD 포인터·GPSVersionID·GPSLatitudeRef가 있는 EXIF,
XMP, IPTC, COM, ICC 세그먼트를 넣었다. PNG에는 eXIf/tEXt/iTXt/zTXt를 넣었다.
GIF에는 주석과 XMP 응용 확장을 넣고, GIF와 WebP에는 두 프레임(100/200ms)과 반복 정보를 넣었다.
WebP에는 EXIF와 XMP를 넣었다. 모든 위치·텍스트 값은 합성 테스트 데이터다.

`stripped.*`는 별도의 Python 바이트 필터로 대상 세그먼트/청크/확장만 제거한 기준 자료다.
Pillow로 입력·출력을 디코딩하여 형식, 프레임 수 및 모든 프레임의 RGBA 픽셀 일치를 확인했다.
Java 테스트는 업로드로 실제 저장된 바이트가 이 기준 자료와 같은지 검사한다.
JPEG/PNG/GIF는 Java ImageIO로도 결과를 디코딩한다. JDK 기본 WebP 디코더는 없으므로
WebP는 독립 디코딩을 확인한 기준 바이트와의 일치 및 프레임/플래그/RIFF 구조를 검사한다.
