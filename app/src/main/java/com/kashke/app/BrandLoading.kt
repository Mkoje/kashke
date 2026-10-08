package com.kashke.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Suppress("DEPRECATION")
private class BrandAnimationView(context: Context) : View(context) {
    private val movie: Movie? = resources.openRawResource(R.raw.kashke_loop).use { Movie.decodeStream(it) }
    private val startedAt = SystemClock.uptimeMillis()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val animation = movie ?: return
        val scale = minOf(width.toFloat() / animation.width(), height.toFloat() / animation.height())
        canvas.save()
        canvas.translate((width - animation.width() * scale) / 2, (height - animation.height() * scale) / 2)
        canvas.scale(scale, scale)
        animation.setTime(((SystemClock.uptimeMillis() - startedAt) % animation.duration().coerceAtLeast(1000)).toInt())
        animation.draw(canvas, 0f, 0f)
        canvas.restore()
        if (isShown) postInvalidateOnAnimation()
    }
}

@Composable
internal fun AppLoadingIndicator(modifier: Modifier = Modifier.size(56.dp)) {
    AndroidView(factory = { BrandAnimationView(it).apply { contentDescription = "Loading KashKe" } },
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Color.White))
}

@Composable
internal fun BrandedLaunchScreen() {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.kashke_logo), contentDescription = "KashKe",
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp))
        Spacer(Modifier.height(24.dp))
        AppLoadingIndicator()
    }
}
