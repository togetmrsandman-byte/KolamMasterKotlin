package com.kolammaster.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap

private val historyGold = Color(0xFFFFC928)
private val historyBody = Color.White.copy(alpha = 0.9f)

@Composable
internal fun HistoryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HistoryHeading()
        Spacer(Modifier.height(20.dp))

        HistoryParagraph(
            buildAnnotatedString {
                append("There are three main types of Kolam categories named Sikku Kolam, Pulli Kolam and Rangoli.")
            }
        )

        HistorySectionTitle("Sikku Kolam & Pulli Kolam")
        HistoryParagraph(buildAnnotatedString {
            bold("Where they originated:")
            append(" ")
            bold("Tamil Nadu (South India).")
        })
        HistoryParagraph(buildAnnotatedString {
            bold("The Origin Story:")
            append(" These practices originated out of prehistoric tribal and early agricultural communities in ancient Tamilakam. The practice started as an ")
            bold("ecological and mathematical necessity.")
        })
        HistoryImage("ancient-village-kolam-and-harvest life.webp")
        HistoryParagraph(buildAnnotatedString {
            append("Ancient Tamils drew grids of dots (")
            bold("Pulli")
            append(") using edible rice flour to feed ants and birds.")
        })
        HistoryImage("sparrows-and-ants-on-kolam.webp")
        HistoryParagraph(buildAnnotatedString {
            append("They connected or looped around the dots because they believed closed loops trapped evil spirits at the doorstep, preventing them from entering the house.")
        })
        HistoryImage("evil-trapped.webp")
        HistoryParagraph(buildAnnotatedString {
            bold("Sikku (or Kambi) Kolam:")
            append(" You lay down dots, but your lines ")
            withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                append("never touch the dots")
            }
            append(". Instead, a single, continuous line twists, turns, and loops around the dots without ever breaking.")
        })
        HistoryImage("sikku-kolam-bubble-picture.png")
        HistoryParagraph(buildAnnotatedString {
            bold("Pulli Kolam:")
            append(" You lay down a grid of dots and connect them using straight or geometric lines to form stars, squares, or flowers.")
        })
        HistoryImage("pulli-kolam-bubble-picture.png")

        HistorySectionTitle("Rangoli")
        HistoryBullet(buildAnnotatedString {
            bold("Where it originated:")
            append(" ")
            bold("Maharashtra and Madhya Pradesh (Central India).")
        })
        HistoryBullet(buildAnnotatedString {
            bold("The Origin Story:")
            append(" This practice originated from the ancient ")
            bold("pastoral and royal communities of Central India")
            append(". Instead of drawing lines to trap spirits, it originated as a form of ")
            bold("floor painting (*dhuli citra*)")
            append(" to decorate courtyards for festivals and royal celebrations. Instead of using a strict dot matrix, people used their fingers to draw freehand animals, flowers, and symbols, filling them with vibrant colored dust to mimic royal carpets.")
        })

        HistorySectionTitle("Sangam Reference")
        HistoryParagraph(buildAnnotatedString {
            append("Early Tamil Sangam literature contains references to decorated entrances and courtyards, showing that the practice of floor decoration existed during that period.")
        })

        HistorySectionTitle("Festival Traditions")
        HistoryParagraph(buildAnnotatedString {
            append("During festivals and special occasions, Kolams are made larger and more elaborate to mark celebrations and welcome visitors.")
        })

        HistorySectionTitle("It is a Playground for Modern Computer Science")
        HistoryParagraph(buildAnnotatedString {
            append("In the 1970s and 1980s, a breakthrough occurred when computer scientists at ")
            pushLink(
                LinkAnnotation.Url(
                    "https://mcc.edu.in/",
                    TextLinkStyles(
                        style = SpanStyle(
                            color = historyGold,
                            fontWeight = FontWeight.Bold,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                )
            )
            bold("Madras Christian College")
            pop()
            append(" (led by Dr. Gift Siromoney) realized that ")
            bold("Sikku Kolams are actually advanced visual algorithms.")
        })
        HistoryBullet(buildAnnotatedString {
            append("They discovered that the rules women use to loop lines around dots perfectly match ")
            bold("formal picture languages")
            append(" and array grammars used in computer programming.")
        })
        HistoryBullet(buildAnnotatedString {
            append("Today, international researchers use Kolam structures to study ")
            bold("knot theory")
            append(", ")
            bold("picture logic")
            append(", DNA splicing models, and advanced robotics trajectory planning.")
        })

        HistorySectionTitle("The \"Brahma Muhurtam\" Timing")
        HistoryParagraph(buildAnnotatedString {
            append("A Kolam is historically tied to a specific window of time called the ")
            bold("Brahma Muhurtam")
            append("—the highly auspicious period that begins roughly ")
            bold("1.5 hours before sunrise.")
        })
        HistoryBullet(buildAnnotatedString {
            append("Culturally, it was believed this is when deities pass over the earth, and an empty threshold would cause them to bypass the home.")
        })
        HistoryBullet(buildAnnotatedString {
            append("Anthropologically, it served as a social signal: a freshly drawn Kolam told the entire village that the women of the household were awake, safe, and the home was ready to receive the community.")
        })

        listOf(
            "tamil-village-1.webp",
            "tamil-village-2.webp",
            "tamil-village-3.webp",
            "tamil-village-4.webp",
            "tamil-village-5.webp",
            "tamil-village-6.webp"
        ).forEach { asset -> HistoryImage(asset) }
        HistoryImage("sikku-kolam-bubble-picture.png")
    }
}

@Composable
private fun HistoryHeading() {
    val border = rememberAssetImage("kolam-border.png")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HistoryTitleBorder(border, mirrored = true)
        Text(
            text = "History",
            modifier = Modifier.padding(horizontal = 10.dp),
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        HistoryTitleBorder(border)
    }
}

@Composable
private fun HistoryTitleBorder(border: ImageBitmap, mirrored: Boolean = false) {
    Box(
        modifier = Modifier.size(width = 38.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = border,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .requiredSize(width = 38.dp, height = 92.dp)
                .then(
                    if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier
                )
        )
    }
}

@Composable
private fun HistorySectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 8.dp),
        color = historyGold,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start
    )
}

@Composable
private fun HistoryParagraph(text: AnnotatedString) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = historyBody,
        fontSize = 15.sp,
        lineHeight = 23.sp
    )
}

@Composable
private fun HistoryBullet(text: AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", color = historyGold, fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
        Text(
            text = text,
            color = historyBody,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )
    }
}

@Composable
private fun HistoryImage(assetName: String) {
    val image = rememberHistoryAsset(assetName)
    Image(
        bitmap = image,
        contentDescription = assetName.substringBeforeLast('.').replace('-', ' '),
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(12.dp))
    )
}

@Composable
private fun rememberHistoryAsset(name: String): ImageBitmap {
    val context = LocalContext.current
    return androidx.compose.runtime.remember(context, name) {
        context.assets.open(name).use(BitmapFactory::decodeStream)
            ?.asImageBitmap()
            ?: error("Could not decode History asset: $name")
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.bold(text: String) {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }
}
