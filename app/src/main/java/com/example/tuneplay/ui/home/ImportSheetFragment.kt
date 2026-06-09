package com.example.tuneplay.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.tuneplay.R
import com.example.tuneplay.databinding.BottomSheetImportBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 导入底部弹窗 — 提供"扫描本地音乐库"和"选择文件"两个选项。
 * 通过回调通知宿主 Fragment 执行具体操作。
 */
class ImportSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetImportBinding? = null
    private val binding get() = _binding!!

    var onScanLibrary: (() -> Unit)? = null
    var onPickFiles: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnScanMediaStore.setOnClickListener {
            onScanLibrary?.invoke()
            dismiss()
        }
        binding.btnPickFiles.setOnClickListener {
            onPickFiles?.invoke()
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ImportSheetFragment"
    }
}
