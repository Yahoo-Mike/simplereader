package com.simplereader.ui.font

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import com.simplereader.R

import org.readium.r2.navigator.preferences.FontFamily

class FontPreviewAdapter(
    private val fonts: List<FontFamily>,
    private var selectedFont: FontFamily,
    private val onFontSelected: (FontFamily) -> Unit
) : RecyclerView.Adapter<FontPreviewAdapter.FontViewHolder>() {

    inner class FontViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fontName: TextView = view.findViewById(R.id.fontName)
        val fontPreview: TextView = view.findViewById(R.id.fontPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FontViewHolder =
        FontViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_font_preview, parent, false))

    override fun onBindViewHolder(holder: FontViewHolder, position: Int) {
        val font = fonts[position]
        holder.fontName.text = font.name
        holder.fontPreview.typeface = font.toTypeface(holder.itemView.context)

        // highlight selected item
        holder.itemView.setBackgroundColor(
            if (font == selectedFont) Color.LTGRAY else Color.TRANSPARENT
        )

        holder.itemView.setOnClickListener {
            if (font != selectedFont) {
                val previousIndex = fonts.indexOf(selectedFont)
                selectedFont = font
                notifyItemChanged(previousIndex)
                notifyItemChanged(position)
                onFontSelected(font) // Don't dismiss
            }
        }
    }

    override fun getItemCount(): Int = fonts.size
}

fun FontFamily.toTypeface(context: Context): Typeface {
    return when (this) {
        // system-loaded fonts
        FontFamily.SERIF -> Typeface.SERIF
        FontFamily.SANS_SERIF -> Typeface.SANS_SERIF
        FontFamily.MONOSPACE -> Typeface.MONOSPACE

        // readium accessibility fonts
        FontFamily.ACCESSIBLE_DFA -> ResourcesCompat.getFont(context, R.font.accessible_dfa)!!
        FontFamily.IA_WRITER_DUOSPACE -> ResourcesCompat.getFont(context, R.font.ia_writer_duospace)!!
        FontFamily.OPEN_DYSLEXIC -> ResourcesCompat.getFont(context, R.font.open_dyslexic)!!

        // FolioReader packaged fonts
        FontFamily.ANDADA -> Typeface.createFromAsset(context.assets, "fonts/andada_regular.otf")
        FontFamily.LATO -> Typeface.createFromAsset(context.assets, "fonts/lato_regular.ttf")
        FontFamily.LORA -> Typeface.createFromAsset(context.assets, "fonts/lora_regular.ttf")
        FontFamily.RALEWAY -> Typeface.createFromAsset(context.assets, "fonts/raleway_regular.ttf")

        else -> throw Exception("unknown font family")
    }
}
