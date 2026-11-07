# Room uses generated adapters. Keep generic and annotation metadata consumed by
# Kotlin serializers and metadata parsers used by provider implementations.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# JAudioTagger's desktop artwork helper references AWT/ImageIO. Android uses
# AndroidArtwork instead; these desktop-only classes are absent from Android.
-dontwarn java.awt.image.BufferedImage
-dontwarn javax.imageio.ImageIO
-dontwarn javax.imageio.stream.ImageInputStream
