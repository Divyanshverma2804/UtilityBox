package com.example.utilitybox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Slide(val imageRes: Int, val title: String, val caption: String)

class SlideshowAdapter(private val slides: List<Slide>) :
    RecyclerView.Adapter<SlideshowAdapter.SlideViewHolder>() {

    inner class SlideViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.slide_image)
        val title: TextView = itemView.findViewById(R.id.slide_title)
        val caption: TextView = itemView.findViewById(R.id.slide_caption)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_slide, parent, false)
        return SlideViewHolder(view)
    }

    override fun onBindViewHolder(holder: SlideViewHolder, position: Int) {
        val slide = slides[position]
        holder.image.setImageResource(slide.imageRes)
        holder.title.text = slide.title
        holder.caption.text = slide.caption
    }

    override fun getItemCount() = slides.size
}
