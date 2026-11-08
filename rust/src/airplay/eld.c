#include <limits.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
#include <libavutil/samplefmt.h>

typedef struct {
    AVCodecContext *codec;
    AVPacket *packet;
    AVFrame *frame;
} Eld;

void pdmvos_eld_free(Eld *decoder) {
    if (!decoder) return;
    av_frame_free(&decoder->frame);
    av_packet_free(&decoder->packet);
    avcodec_free_context(&decoder->codec);
    av_free(decoder);
}

Eld *pdmvos_eld_open(const unsigned char *config, size_t size, int rate) {
    if (size != 4) return NULL;
    Eld *decoder = av_mallocz(sizeof(*decoder));
    if (!decoder) return NULL;
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
    if (!codec) goto fail;
    decoder->codec = avcodec_alloc_context3(codec);
    decoder->packet = av_packet_alloc();
    decoder->frame = av_frame_alloc();
    if (!decoder->codec || !decoder->packet || !decoder->frame) goto fail;
    decoder->codec->extradata = av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!decoder->codec->extradata) goto fail;
    memcpy(decoder->codec->extradata, config, size);
    decoder->codec->extradata_size = (int)size;
    decoder->codec->sample_rate = rate;
    av_channel_layout_default(&decoder->codec->ch_layout, 2);
    if (avcodec_open2(decoder->codec, codec, NULL) < 0) goto fail;
    return decoder;
fail:
    pdmvos_eld_free(decoder);
    return NULL;
}

int pdmvos_eld_decode(Eld *decoder, const unsigned char *data, size_t size,
                      float *output, size_t capacity) {
    if (size == 0 || size > INT_MAX || av_new_packet(decoder->packet, (int)size) < 0) return 0;
    memcpy(decoder->packet->data, data, size);
    int status = avcodec_send_packet(decoder->codec, decoder->packet);
    av_packet_unref(decoder->packet);
    if (status < 0) return status;
    status = avcodec_receive_frame(decoder->codec, decoder->frame);
    if (status < 0) return status == AVERROR(EAGAIN) ? 0 : status;
    const AVFrame *frame = decoder->frame;
    size_t samples = (size_t)frame->nb_samples;
    int count = AVERROR_INVALIDDATA;
    if (frame->ch_layout.nb_channels == 2 && samples <= capacity / 2 &&
        frame->format == AV_SAMPLE_FMT_FLTP) {
        const float *left = (const float *)frame->extended_data[0];
        const float *right = (const float *)frame->extended_data[1];
        for (size_t index = 0; index < samples; ++index) {
            output[index * 2] = left[index];
            output[index * 2 + 1] = right[index];
        }
        count = (int)(samples * 2);
    }
    av_frame_unref(decoder->frame);
    return count;
}
