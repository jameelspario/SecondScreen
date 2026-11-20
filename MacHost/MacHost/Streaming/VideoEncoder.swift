import Foundation
import VideoToolbox
import CoreMedia
import CoreFoundation

/// Hardware H.264 encoder using VideoToolbox. Outputs Annex-B format NALUs with SPS/PPS.
public final class VideoEncoder {
    public var onEncodedData: ((Data) -> Void)?

    private var session: VTCompressionSession?
    private let width: Int
    private let height: Int
    private let bitrate: Int

    public init(width: Int, height: Int, bitrate: Int = 8_000_000) {
        self.width = width
        self.height = height
        self.bitrate = bitrate
        setupSession()
    }

    deinit {
        if let session {
            VTCompressionSessionInvalidate(session)
        }
    }

    public var forceNextKeyFrame: Bool = true
    private var cachedHeaders = Data()

    public func encode(pixelBuffer: CVPixelBuffer, presentationTime: CMTime) {
        guard let session else { return }

        var frameProperties: CFDictionary? = nil
        if forceNextKeyFrame {
            frameProperties = [
                kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue
            ] as CFDictionary
            forceNextKeyFrame = false
        }

        VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: presentationTime,
            duration: .invalid,
            frameProperties: frameProperties,
            infoFlagsOut: nil,
            outputHandler: { [weak self] status, _, sampleBuffer in
                guard status == noErr, let sampleBuffer else { return }
                self?.handleEncodedFrame(sampleBuffer)
            }
        )
    }

    private func setupSession() {
        let encoderSpecification: CFDictionary = [
            kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: false
        ] as CFDictionary

        let status = VTCompressionSessionCreate(
            allocator: nil,
            width: Int32(width),
            height: Int32(height),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: encoderSpecification,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &session
        )
        guard status == noErr, let session else { return }

        // Baseline profile for universal compatibility with Android MediaCodec
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel,
                             value: kVTProfileLevel_H264_Baseline_AutoLevel)
        // Real-time ultra low-latency encoding
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime,
                             value: kCFBooleanTrue)
        // Disable B-frame reordering for immediate display
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering,
                             value: kCFBooleanFalse)
        // Keyframe interval
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval,
                             value: 60 as CFNumber)
        // Bitrate target
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate,
                             value: bitrate as CFNumber)

        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    private func handleEncodedFrame(_ sampleBuffer: CMSampleBuffer) {
        var annexB = Data()

        // Check for IDR / Keyframe -> extract and prepend SPS + PPS
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[CFString: Any]]
        let isKeyframe = attachments?.first?[kCMSampleAttachmentKey_NotSync as CFString] == nil

        if isKeyframe {
            if let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
                var paramCount = 0
                CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: 0,
                    parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                    parameterSetCountOut: &paramCount, nalUnitHeaderLengthOut: nil)

                var headerData = Data()
                for i in 0..<paramCount {
                    var paramPtr: UnsafePointer<UInt8>? = nil
                    var paramLen = 0
                    let err = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                        format, parameterSetIndex: i,
                        parameterSetPointerOut: &paramPtr,
                        parameterSetSizeOut: &paramLen,
                        parameterSetCountOut: nil,
                        nalUnitHeaderLengthOut: nil)
                    if err == noErr, let paramPtr {
                        headerData.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
                        headerData.append(paramPtr, count: paramLen)
                    }
                }
                if !headerData.isEmpty {
                    cachedHeaders = headerData
                }
            }
            if !cachedHeaders.isEmpty {
                annexB.append(cachedHeaders)
            }
        }

        // Convert AVCC NAL units to Annex-B start codes
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        var totalLength = 0
        var dataPtr: UnsafeMutablePointer<CChar>? = nil
        let err = CMBlockBufferGetDataPointer(
            blockBuffer,
            atOffset: 0,
            lengthAtOffsetOut: nil,
            totalLengthOut: &totalLength,
            dataPointerOut: &dataPtr)
        guard err == noErr, let dataPtr else { return }

        let rawPtr = UnsafeRawPointer(dataPtr)
        var offset = 0
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        while offset < totalLength {
            guard offset + 4 <= totalLength else { break }

            // Safely read 4-byte big-endian NAL unit length without alignment crash
            var rawLength: UInt32 = 0
            memcpy(&rawLength, rawPtr.advanced(by: offset), 4)
            let nalLength = Int(UInt32(bigEndian: rawLength))
            offset += 4

            guard offset + nalLength <= totalLength else { break }

            annexB.append(contentsOf: startCode)
            annexB.append(Data(bytes: rawPtr.advanced(by: offset), count: nalLength))
            offset += nalLength
        }

        onEncodedData?(annexB)
    }
}
